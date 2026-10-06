import assert from "node:assert/strict"
import test from "node:test"
import { createSpeechPlayer, splitSpeechChunks } from "../src/utils/speech-player.js"

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

const flush = () => new Promise((resolve) => setImmediate(resolve))
const longText = Array(80).fill("word").join(" ")

function harness({ deferredPlay = false, abortRequests = true } = {}) {
  const requests = [], audio = [], released = [], errors = [], firstAudio = []
  let activeRequests = 0, maxRequests = 0, clock = 0
  const player = createSpeechPlayer({
    async fetchAudio(text, language, signal) {
      const pending = deferred()
      const request = { text, language, signal, ...pending }
      requests.push(request)
      activeRequests++
      maxRequests = Math.max(maxRequests, activeRequests)
      if (abortRequests) {
        signal.addEventListener("abort", () => {
          pending.reject(new DOMException("Cancelled", "AbortError"))
        }, { once: true })
      }
      try {
        return await pending.promise
      } finally {
        activeRequests--
      }
    },
    createObjectURL: (blob) => `blob:${blob.label}`,
    revokeObjectURL: (url) => released.push(url),
    createAudio(url) {
      const playback = deferred()
      const element = {
        url, playback, paused: false, played: false,
        play() {
          this.played = true
          return deferredPlay ? playback.promise : Promise.resolve()
        },
        pause() { this.paused = true },
      }
      audio.push(element)
      return element
    },
    now: () => clock,
  })
  return {
    player, requests, audio, released, errors, firstAudio,
    play: (text = longText, language = "fa") => player.play(text, language, {
      onFirstAudio: (duration) => firstAudio.push(duration),
      onError: (error) => errors.push(error),
    }),
    setClock: (value) => { clock = value },
    maxRequests: () => maxRequests,
  }
}

test("short first chunk preserves every word and larger later chunks", () => {
  const chunks = splitSpeechChunks(longText)
  assert.equal(chunks.length, 3)
  assert.ok(chunks[0].length <= 90)
  assert.ok(chunks[1].length > 90 && chunks[1].length <= 180)
  assert.equal(chunks.join(" "), longText)
  assert.deepEqual(splitSpeechChunks("  \n \t "), [])
  const longWord = "字".repeat(200)
  assert.deepEqual(splitSpeechChunks(longWord), [longWord])
})

test("Persian/Urdu text keeps punctuation and can start at a sentence boundary", () => {
  for (const sentence of [
    "سلام دوست من امروز هوا بسیار خوب است۔",
    "آپ کیسے ہیں آج کا موسم بہت اچھا ہے؟",
  ]) {
    const text = `${sentence} ${Array(35).fill("سلام").join(" ")}`
    const chunks = splitSpeechChunks(text)
    assert.equal(chunks[0], sentence)
    assert.equal(chunks.join(" "), text)
  }
})

test("one lookahead starts before onended and playback remains ordered", async () => {
  const h = harness()
  const done = h.play()
  assert.equal(h.requests.length, 1)
  h.requests[0].resolve({ label: "first" })
  await flush()
  assert.equal(h.audio[0].url, "blob:first")
  assert.equal(h.audio[0].played, true)
  assert.equal(h.requests.length, 2, "next synthesis starts during current playback")
  assert.equal(h.requests[0].signal, h.requests[1].signal)
  assert.equal(h.requests[1].language, "fa")

  h.requests[1].resolve({ label: "second" })
  await flush()
  assert.equal(h.audio.length, 1)
  assert.equal(h.requests.length, 2, "only one future chunk is prefetched")
  h.audio[0].onended()
  await flush()
  assert.deepEqual(h.audio.map((element) => element.url), ["blob:first", "blob:second"])
  assert.deepEqual(h.released, ["blob:first"])
  assert.equal(h.requests.length, 3)
  h.requests[2].resolve({ label: "third" })
  await flush()
  h.audio[1].onended()
  await flush()
  assert.equal(h.audio[2].url, "blob:third")
  h.audio[2].onended()
  await done
  assert.equal(h.maxRequests(), 1)
  assert.deepEqual(h.released, ["blob:first", "blob:second", "blob:third"])
  assert.ok(h.audio.every((element) => element.paused && element.onended === null && element.onerror === null))
  assert.equal(h.errors.length, 0)
  assert.equal(h.firstAudio.length, 1)
})

test("stop aborts lookahead and releases current audio without reporting cancellation", async () => {
  const h = harness()
  const done = h.play()
  h.requests[0].resolve({ label: "current" })
  await flush()
  assert.equal(h.requests.length, 2)
  const oldEnded = h.audio[0].onended
  h.player.stop()
  oldEnded()
  await done
  await flush()
  assert.equal(h.requests[1].signal.aborted, true)
  assert.equal(h.audio.length, 1)
  assert.deepEqual(h.released, ["blob:current"])
  assert.equal(h.errors.length, 0)
})

test("replacement cancels an old fetch even if it ignores the abort signal", async () => {
  const h = harness({ abortRequests: false })
  const old = h.play("old speech", "ur")
  const replacement = h.play("new speech", "fa")
  await old
  assert.equal(h.requests[0].signal.aborted, true)
  h.requests[0].resolve({ label: "old" })
  h.requests[1].resolve({ label: "new" })
  await flush()
  assert.deepEqual(h.audio.map((element) => element.url), ["blob:new"])
  h.audio[0].onended()
  await replacement
  assert.equal(h.errors.length, 0)
})

test("stop interrupts a pending Audio.play and cannot start a later chunk", async () => {
  const h = harness({ deferredPlay: true })
  const done = h.play()
  h.requests[0].resolve({ label: "first" })
  await flush()
  assert.equal(h.requests.length, 1)
  h.player.stop()
  await done
  h.audio[0].playback.resolve()
  await flush()
  assert.equal(h.requests.length, 1)
  assert.deepEqual(h.firstAudio, [])
  assert.deepEqual(h.released, ["blob:first"])
})

test("first audio timing includes fetch and waiting for playback to begin", async () => {
  const h = harness({ deferredPlay: true })
  const done = h.play("سلام", "ur")
  h.setClock(100)
  h.requests[0].resolve({ label: "first" })
  await flush()
  assert.deepEqual(h.firstAudio, [])
  h.setClock(275)
  h.audio[0].playback.resolve()
  await flush()
  assert.deepEqual(h.firstAudio, [275])
  h.audio[0].onended()
  await done
})

test("prefetch failures are handled immediately and reported once when needed", async () => {
  const h = harness()
  const done = h.play()
  h.requests[0].resolve({ label: "first" })
  await flush()
  h.requests[1].reject(new Error("Missing Persian model"))
  await flush()
  assert.equal(h.errors.length, 0, "finish the current chunk before reporting next failure")
  h.audio[0].onended()
  await done
  assert.equal(h.errors.length, 1)
  assert.match(h.errors[0].message, /Missing Persian model/)
  assert.deepEqual(h.released, ["blob:first"])
  assert.equal(h.audio.length, 1)
})

test("audio errors interrupt pending play, cancel lookahead, and clean up once", async () => {
  const h = harness({ deferredPlay: true })
  const done = h.play("سلام", "ur")
  h.requests[0].resolve({ label: "broken" })
  await flush()
  h.audio[0].onerror()
  await done
  h.audio[0].playback.reject(new Error("play failed"))
  await flush()
  assert.equal(h.errors.length, 1)
  assert.match(h.errors[0].message, /ur speech audio/)
  assert.deepEqual(h.released, ["blob:broken"])
  assert.deepEqual(h.firstAudio, [])
})

test("empty speech stops an existing utterance without issuing a request", async () => {
  const h = harness()
  const old = h.play()
  await h.play("  ")
  await old
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].signal.aborted, true)
  assert.equal(h.audio.length, 0)
  assert.equal(h.errors.length, 0)
})
