import assert from "node:assert/strict"
import test from "node:test"
import { AVAILABLE_LANGUAGES, buildTranslationPrompt, textDirection } from "../src/utils/languages.js"
import { transcribeAudio, translateText, fetchSpeechAudio } from "../src/utils/api.js"

test("existing languages and initial selections are preserved; fa/ur use their own voices", () => {
  assert.deepEqual(AVAILABLE_LANGUAGES.map((lang) => lang.code), ["ar", "en", "es", "ja", "zh", "ko", "fa", "ur"])
  for (const code of ["fa", "ur"]) {
    assert.equal(AVAILABLE_LANGUAGES.find((lang) => lang.code === code).ttsLang, code)
  }
})

test("Persian, Urdu, Arabic are RTL while all other languages stay LTR", () => {
  for (const lang of AVAILABLE_LANGUAGES) {
    assert.equal(textDirection(lang.code), ["ar", "fa", "ur"].includes(lang.code) ? "rtl" : "ltr")
  }
  assert.equal(textDirection(undefined), "ltr")
})

test("every directed language pair reaches local Gemma with the correct names", async () => {
  const originalFetch = globalThis.fetch
  try {
    for (const source of AVAILABLE_LANGUAGES) {
      for (const target of AVAILABLE_LANGUAGES) {
        if (source.code === target.code) continue
        globalThis.fetch = async (url, options) => {
          assert.equal(decodeURIComponent(url), "/proxy?url=http://localhost:9379/v1/chat/completions")
          const payload = JSON.parse(options.body)
          assert.equal(payload.model, "gemma4-e2b")
          assert.equal(payload.temperature, 0)
          assert.deepEqual(Object.keys(payload).sort(), ["messages", "model", "temperature"])
          assert.match(payload.messages[0].content, new RegExp(`from ${source.name} into ${target.name}`))
          assert.match(payload.messages[0].content, /Return only valid JSON: \{"translation":"translated text"\}/)
          assert.ok(payload.messages[0].content.length < 200)
          assert.equal(payload.messages[1].content, "recognized speech")
          return new Response(JSON.stringify({choices: [{message: {content: '{"translation":"translated speech"}'}}]}))
        }
        const result = await translateText("recognized speech", {
          endpointUrl: "http://localhost:9379/v1", useProxy: true, modelName: "gemma4-e2b",
          systemPrompt: buildTranslationPrompt(source, target),
        })
        assert.equal(result.translation, "translated speech")
        assert.equal(result.tokens, null)
      }
    }
  } finally {
    globalThis.fetch = originalFetch
  }
})

test("all source codes use the unchanged browser PCM transcription API", async () => {
  const originalFetch = globalThis.fetch
  try {
    for (const language of AVAILABLE_LANGUAGES) {
      globalThis.fetch = async (url, options) => {
        assert.equal(url, "/api/stt")
        assert.equal(options.method, "POST")
        assert.deepEqual(JSON.parse(options.body), {audio_base64: "AAAAAA==", language: language.code})
        return new Response(JSON.stringify({text: "سلام"}))
      }
      assert.equal(await transcribeAudio("AAAAAA==", language.code), "سلام")
    }
  } finally {
    globalThis.fetch = originalFetch
  }
})

test("TTS requests keep the selected Persian/Urdu language and UTF-8 text", async () => {
  const originalFetch = globalThis.fetch
  try {
    for (const code of ["fa", "ur"]) {
      globalThis.fetch = async (url) => {
        const parsed = new URL(url, "http://localhost:3000")
        assert.equal(parsed.pathname, "/api/tts")
        assert.equal(parsed.searchParams.get("lang"), code)
        assert.equal(parsed.searchParams.get("text"), "سلام دنیا")
        return new Response(new Blob(["mock WAV"], {type: "audio/wav"}))
      }
      const blob = await fetchSpeechAudio("سلام دنیا", code)
      assert.equal(blob.type, "audio/wav")
    }
  } finally {
    globalThis.fetch = originalFetch
  }
})

test("missing engine errors reach the frontend instead of a silent language fallback", async () => {
  const originalFetch = globalThis.fetch
  try {
    globalThis.fetch = async () => new Response(JSON.stringify({error: "Set WHISPER_MODEL_PATH or run ./setup.sh"}), {status: 503})
    await assert.rejects(transcribeAudio("AAAAAA==", "fa"), /WHISPER_MODEL_PATH/)
    globalThis.fetch = async () => new Response(JSON.stringify({error: "Missing Urdu espeak-ng voice"}), {status: 503})
    await assert.rejects(fetchSpeechAudio("سلام", "ur"), /Urdu espeak-ng voice/)
  } finally {
    globalThis.fetch = originalFetch
  }
})
