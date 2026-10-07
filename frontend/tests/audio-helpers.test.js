import assert from "node:assert/strict"
import test from "node:test"
import { resample, getMergedSamples } from "../src/utils/audioHelpers.js"

function tone(frequency, rate) {
  return Float32Array.from({ length: rate }, (_, index) => Math.sin(2 * Math.PI * frequency * index / rate))
}

function rms(samples) {
  // Ignore endpoint transients when measuring the filter response.
  const interior = samples.subarray(100, samples.length - 100)
  return Math.sqrt(interior.reduce((sum, sample) => sum + sample * sample, 0) / interior.length)
}

test("downsampling rejects high-frequency aliases while retaining speech frequencies", () => {
  for (const rate of [44100, 48000]) {
    const speech = resample(tone(1000, rate), rate, 16000)
    const noise = resample(tone(12000, rate), rate, 16000)
    assert.equal(speech.length, 16000)
    assert.ok(Math.abs(rms(speech) - Math.SQRT1_2) < 0.005)
    assert.ok(rms(noise) < 0.01, `12kHz noise must not alias into speech at capture rate ${rate}`)
  }
})

test("resampling preserves duration, finite samples and constant amplitude", () => {
  for (const rate of [16000, 22050, 44100, 48000, 96000]) {
    const input = new Float32Array(rate * 2).fill(0.25)
    const output = resample(input, rate, 16000)
    assert.equal(output.length, 32000)
    assert.ok(output.every((sample) => Number.isFinite(sample) && Math.abs(sample - 0.25) < 1e-6))
  }
  assert.equal(resample(new Float32Array(), 48000, 16000).length, 0)
})

test("already-16kHz audio remains unchanged; upsampling and tiny buffers remain valid", () => {
  const input = new Float32Array([0, 0.5, -0.5])
  assert.equal(resample(input, 16000, 16000), input)
  assert.deepEqual(resample(new Float32Array([0.25]), 48000, 16000), new Float32Array())
  assert.equal(resample(input, 8000, 16000).length, 6)
  assert.deepEqual(getMergedSamples([input, input]), new Float32Array([0, 0.5, -0.5, 0, 0.5, -0.5]))
  assert.throws(() => resample(input, 0, 16000), /sample rates/)
})
