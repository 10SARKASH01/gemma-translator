/**
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Audio utilities for the recorder's Float32 PCM pipeline.

// Concatenate the per-callback Float32Array chunks into one buffer.
export function getMergedSamples(recordedSamples) {
  let totalLength = 0
  for (let i = 0; i < recordedSamples.length; i++) {
    totalLength += recordedSamples[i].length
  }
  const merged = new Float32Array(totalLength)
  let offset = 0
  for (let i = 0; i < recordedSamples.length; i++) {
    merged.set(recordedSamples[i], offset)
    offset += recordedSamples[i].length
  }
  return merged
}

// Band-limited downsampling avoids folding noise above 8kHz into the 16kHz
// speech signal. Cache phase kernels for the current capture rate; no model
// dependency or network request is needed. Upsampling still uses interpolation.
let filterCache = null

function downsampleFilter(ratio) {
  if (filterCache?.ratio === ratio) return filterCache
  const radius = Math.ceil(12 * ratio)
  const cutoff = 0.45 / ratio
  const phases = 128
  const kernels = Array.from({ length: phases }, (_, phase) => {
    const weights = new Float64Array(2 * radius + 1)
    let total = 0
    for (let tap = -radius; tap <= radius; tap++) {
      const distance = tap - phase / phases
      if (Math.abs(distance) >= radius) continue
      const angle = 2 * Math.PI * cutoff * distance
      const sinc = angle === 0 ? 1 : Math.sin(angle) / angle
      const window = 0.42 + 0.5 * Math.cos(Math.PI * distance / radius)
        + 0.08 * Math.cos(2 * Math.PI * distance / radius)
      weights[tap + radius] = 2 * cutoff * sinc * window
      total += weights[tap + radius]
    }
    for (let tap = 0; tap < weights.length; tap++) weights[tap] /= total
    return weights
  })
  filterCache = { ratio, radius, phases, kernels }
  return filterCache
}

export function resample(audioBuffer, originalSampleRate, targetSampleRate) {
  if (!Number.isFinite(originalSampleRate) || originalSampleRate <= 0
    || !Number.isFinite(targetSampleRate) || targetSampleRate <= 0) {
    throw new Error("Audio sample rates must be positive finite numbers")
  }
  if (originalSampleRate === targetSampleRate) {
    return audioBuffer
  }
  const ratio = originalSampleRate / targetSampleRate
  const newLength = Math.round(audioBuffer.length / ratio)
  const result = new Float32Array(newLength)
  const filter = ratio > 1 ? downsampleFilter(ratio) : null
  for (let i = 0; i < newLength; i++) {
    const position = i * ratio
    let index = Math.floor(position)
    const fraction = position - index
    if (filter) {
      let phase = Math.round(fraction * filter.phases)
      if (phase === filter.phases) { index++; phase = 0 }
      const weights = filter.kernels[phase]
      let sample = 0
      for (let tap = 0; tap < weights.length; tap++) {
        const sourceIndex = Math.max(0, Math.min(audioBuffer.length - 1, index + tap - filter.radius))
        sample += audioBuffer[sourceIndex] * weights[tap]
      }
      result[i] = sample
      continue
    }
    const sampleCurrent = audioBuffer[index]
    const sampleNext =
      index + 1 < audioBuffer.length ? audioBuffer[index + 1] : sampleCurrent
    result[i] = sampleCurrent + fraction * (sampleNext - sampleCurrent)
  }
  return result
}

// Base64-encode a Blob, stripping the "data:...;base64," data-URL prefix.
export function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onloadend = () => resolve(reader.result.split(",")[1])
    reader.onerror = reject
    reader.readAsDataURL(blob)
  })
}
