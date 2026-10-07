import assert from "node:assert/strict"
import test from "node:test"
import { createPushToTalk } from "../src/utils/push-to-talk.js"

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const flush = () => new Promise((resolve) => setImmediate(resolve))

function harness() {
  const pendingStart = deferred(), pendingStop = deferred()
  const events = [], audio = [], errors = []
  const control = createPushToTalk({
    start() { events.push("start"); return pendingStart.promise },
    stop() { events.push("stop"); return pendingStop.promise },
    onStart(context) { events.push(context.lane) },
    onEnd() { events.push("end") },
    onAudio(context, data) { audio.push({ context, data }) },
    onError(error) { errors.push(error) },
  })
  return { control, events, audio, errors, pendingStart, pendingStop }
}

test("hold/release forwards the original language pair in either direction", async () => {
  for (const context of [
    { lane: 1, src: "fr", dst: "fa" },
    { lane: 2, src: "ur", dst: "en" },
  ]) {
    const h = harness()
    assert.equal(h.control.press(context, "pointer:4"), true)
    h.pendingStart.resolve(true)
    await flush()
    h.control.release("pointer:4")
    h.pendingStop.resolve({ base64Data: "AAAAAA==" })
    await flush()
    assert.deepEqual(h.audio, [{ context, data: { base64Data: "AAAAAA==" } }])
    assert.deepEqual(h.events, [context.lane, "start", "stop", "end"])
    assert.equal(h.control.busy, false)
  }
})

test("a quick touch released during permission stops the late mic and discards audio", async () => {
  const h = harness()
  h.control.press({ lane: 1 }, "pointer:4")
  h.control.release("pointer:4")
  assert.equal(h.control.busy, true)
  assert.equal(h.control.press({ lane: 2 }, "pointer:5"), false)
  h.pendingStart.resolve(true)
  await flush()
  assert.deepEqual(h.events, [1, "start", "stop"])
  h.pendingStop.resolve({ base64Data: "too late" })
  await flush()
  assert.deepEqual(h.audio, [])
  assert.equal(h.control.busy, false)
})

test("another finger or keyboard release cannot stop the current touch", async () => {
  const h = harness()
  h.control.press({ lane: 1 }, "pointer:4")
  assert.equal(h.control.press({ lane: 2 }, "keyboard:x"), false)
  h.pendingStart.resolve(true)
  await flush()
  for (const owner of ["pointer:5", "keyboard:x", "keyboard:z"]) h.control.release(owner)
  assert.deepEqual(h.events, [1, "start"])
  h.control.release("pointer:4")
  // Duplicate release/lost capture must not stop twice or discard valid speech.
  h.control.release("pointer:4", true)
  h.pendingStop.resolve({ base64Data: "speech" })
  await flush()
  assert.equal(h.audio.length, 1)
  assert.equal(h.events.filter((e) => e === "stop").length, 1)
})

test("keyboard auto-repeat and touch input share the same recording lock", async () => {
  const h = harness()
  assert.equal(h.control.press({ lane: 2 }, "keyboard:x"), true)
  assert.equal(h.control.press({ lane: 2 }, "keyboard:x"), false)
  assert.equal(h.control.press({ lane: 1 }, "pointer:4"), false)
  h.pendingStart.resolve(true)
  await flush()
  h.control.release("pointer:4")
  assert.equal(h.control.busy, true)
  h.control.release("keyboard:x")
  assert.equal(h.control.press({ lane: 1 }, "pointer:4"), false)
  h.pendingStop.resolve({ base64Data: "speech" })
  await flush()
  assert.equal(h.audio[0].context.lane, 2)
})

test("pointer cancellation and window/settings cancellation release mic without translation", async () => {
  for (const cancel of [
    (control) => control.release("pointer:4", true),
    (control) => control.cancel(),
  ]) {
    const h = harness()
    h.control.press({ lane: 1 }, "pointer:4")
    h.pendingStart.resolve(true)
    await flush()
    cancel(h.control)
    h.pendingStop.resolve({ base64Data: "discard" })
    await flush()
    assert.deepEqual(h.audio, [])
    assert.equal(h.control.busy, false)
  }
})

test("cancellation during permission also cleans up the eventual mic", async () => {
  const h = harness()
  h.control.press({ lane: 1 }, "pointer:4")
  h.control.cancel()
  h.pendingStart.resolve(true)
  await flush()
  h.pendingStop.resolve(null)
  await flush()
  assert.deepEqual(h.events, [1, "start", "stop", "end"])
  assert.deepEqual(h.audio, [])
})

test("permission denial and start/stop errors release the recording lock", async () => {
  for (const failure of ["denied", "start", "stop"]) {
    const h = harness()
    h.control.press({ lane: 1 }, "pointer:4")
    if (failure === "denied") h.pendingStart.resolve(false)
    else if (failure === "start") h.pendingStart.reject(new Error("mic failed"))
    else {
      h.pendingStart.resolve(true)
      await flush()
      h.control.release("pointer:4")
      h.pendingStop.reject(new Error("encoding failed"))
    }
    await flush()
    assert.equal(h.control.busy, false)
    assert.deepEqual(h.audio, [])
    assert.equal(h.errors.length, failure === "denied" ? 0 : 1)
  }
})

test("a synchronous audio setup failure clears the lock", () => {
  const errors = []
  const control = createPushToTalk({
    start() { throw new Error("AudioContext unavailable") },
    stop() {}, onStart() {}, onEnd() {}, onAudio() {},
    onError(error) { errors.push(error.message) },
  })
  assert.equal(control.press({ lane: 1 }, "pointer:4"), false)
  assert.equal(control.busy, false)
  assert.deepEqual(errors, ["AudioContext unavailable"])
})
