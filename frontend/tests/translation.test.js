import assert from "node:assert/strict"
import test from "node:test"
import { translateText } from "../src/utils/api.js"

const config = {
  endpointUrl: "http://localhost:9379/v1",
  useProxy: true,
  modelName: "gemma4-e2b",
  systemPrompt: "Translate from English into Persian. Return only JSON.",
}

test("translation timing includes receiving and parsing the complete response", async () => {
  const originalFetch = globalThis.fetch
  const performanceDescriptor = Object.getOwnPropertyDescriptor(globalThis, "performance")
  let time = 100
  try {
    Object.defineProperty(globalThis, "performance", {
      configurable: true,
      value: { now: () => time },
    })
    globalThis.fetch = async () => {
      time = 400 // Response headers arrive before the body.
      return {
        ok: true,
        json: async () => {
          time = 2150
          return { choices: [{ message: { content: '{"translation":"سلام"}' } }] }
        },
      }
    }
    const result = await translateText("Hello", config)
    assert.equal(result.duration, "2.05")
    assert.equal(result.translation, "سلام")
    assert.equal(result.tokens, null)
  } finally {
    globalThis.fetch = originalFetch
    Object.defineProperty(globalThis, "performance", performanceDescriptor)
  }
})

test("translation still parses bare JSON, fenced JSON, and raw language text", async () => {
  const originalFetch = globalThis.fetch
  try {
    for (const [content, expected] of [
      ['{"translation":"ہیلو"}', "ہیلو"],
      ['```json\n{"translation":"سلام دنیا"}\n```', "سلام دنیا"],
      ['```\n{"translation":"こんにちは"}\n```', "こんにちは"],
      ["مرحبا بالعالم", "مرحبا بالعالم"],
      ['{"translation":""}', ""],
    ]) {
      globalThis.fetch = async () => new Response(JSON.stringify({ choices: [{ message: { content } }] }))
      assert.equal((await translateText("Hello", config)).translation, expected)
    }
  } finally {
    globalThis.fetch = originalFetch
  }
})

test("token counts distinguish unavailable usage from a reported zero", async () => {
  const originalFetch = globalThis.fetch
  try {
    for (const [usage, expected] of [
      [undefined, null],
      [{}, null],
      [{ total_tokens: 0 }, 0],
      [{ total_tokens: 37 }, 37],
      [{ total_tokens: "37" }, null],
      [{ total_tokens: -1 }, null],
    ]) {
      globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: '{"translation":"translated"}' } }],
        usage,
      }))
      assert.equal((await translateText("Hello", config)).tokens, expected)
    }
  } finally {
    globalThis.fetch = originalFetch
  }
})

test("translation preserves backend failures and malformed response errors", async () => {
  const originalFetch = globalThis.fetch
  try {
    globalThis.fetch = async () => new Response("Local model unavailable", { status: 503 })
    await assert.rejects(translateText("Hello", config), /API 503: Local model unavailable/)
    globalThis.fetch = async () => new Response("Invalid model response")
    await assert.rejects(translateText("Hello", config), SyntaxError)
  } finally {
    globalThis.fetch = originalFetch
  }
})
