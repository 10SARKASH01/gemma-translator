/**
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */

// Start with a short phrase, then use larger chunks to limit synthesis overhead.
// Keep words intact, including Persian/Urdu punctuation and long unspaced text.
export function splitSpeechChunks(text, firstLimit = 90, limit = 180) {
  const chunks = []
  let chunk = ""
  for (const word of text.trim().split(/\s+/)) {
    if (!word) continue
    const chunkLimit = chunks.length === 0 ? firstLimit : limit
    const candidate = chunk ? `${chunk} ${word}` : word
    if (chunk && candidate.length > chunkLimit) {
      chunks.push(chunk)
      chunk = word
    } else {
      chunk = candidate
    }
    if (chunks.length === 0 && chunk.length >= 30 && /[.!?。！？؟۔]$/.test(word)) {
      chunks.push(chunk)
      chunk = ""
    }
  }
  if (chunk) chunks.push(chunk)
  return chunks
}

// One current Audio element and at most one future WAV. A shared abort signal
// cancels the entire utterance when recording starts or another result arrives.
export function createSpeechPlayer({
  fetchAudio,
  createAudio = (url) => new Audio(url),
  createObjectURL = (blob) => URL.createObjectURL(blob),
  revokeObjectURL = (url) => URL.revokeObjectURL(url),
  now = () => performance.now(),
}) {
  let currentSession = null

  const releaseAudio = (session) => {
    if (session.audio) {
      session.audio.onended = null
      session.audio.onerror = null
      session.audio.pause()
      session.audio = null
    }
    if (session.url) {
      revokeObjectURL(session.url)
      session.url = null
    }
  }

  const stop = () => {
    if (!currentSession) return
    const session = currentSession
    currentSession = null
    session.controller.abort()
    session.cancel()
    releaseAudio(session)
  }

  const play = async (text, language, { onFirstAudio, onError } = {}) => {
    stop()
    const chunks = splitSpeechChunks(text)
    if (!chunks.length) return

    const session = { controller: new AbortController(), audio: null, url: null }
    session.cancelled = new Promise((resolve) => { session.cancel = resolve })
    currentSession = session
    const isCurrent = () => currentSession === session
    const started = now()

    const fail = (error) => {
      if (!isCurrent()) return
      stop()
      onError?.(error)
    }

    // Settle errors immediately, even when a prefetched response is not needed
    // until the current chunk ends. This prevents unhandled promise rejections.
    const fetchChunk = async (index) => {
      try {
        const blob = await fetchAudio(chunks[index], language, session.controller.signal)
        return { blob }
      } catch (error) {
        return { error }
      }
    }

    try {
      let pending = fetchChunk(0)
      for (let index = 0; index < chunks.length; index++) {
        const result = await Promise.race([pending, session.cancelled])
        if (!isCurrent()) return
        if (result.error) throw result.error

        session.url = createObjectURL(result.blob)
        const audio = createAudio(session.url)
        session.audio = audio
        audio.volume = 1.0
        const ended = new Promise((resolve) => { audio.onended = resolve })
        audio.onerror = () => fail(new Error(`Could not play ${language} speech audio.`))

        await Promise.race([audio.play(), session.cancelled])
        if (!isCurrent()) return
        if (index === 0) onFirstAudio?.(now() - started)

        // Prepare exactly one following chunk during playback, rather than
        // waiting for onended before asking the backend to synthesize it.
        if (index + 1 < chunks.length) pending = fetchChunk(index + 1)
        await Promise.race([ended, session.cancelled])
        if (!isCurrent()) return
        releaseAudio(session)
      }
      stop()
    } catch (error) {
      fail(error)
    }
  }

  return { play, stop }
}
