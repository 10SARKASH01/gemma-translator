/**
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */

// Manual linguistic check against the installed Pi model, bypassing STT/TTS.
// References illustrate meaning; different correct Persian wording is valid.
import { AVAILABLE_LANGUAGES, buildTranslationPrompt } from "../src/utils/languages.js"
import { getNormalizedBaseUrl, translateText } from "../src/utils/api.js"

const cases = [
  {
    text: "Could you give me a hand with this?",
    reference: "می‌توانی در این کار کمکم کنی؟",
    check: "A request for help, not a literal hand; translate the question instead of answering it.",
  },
  {
    text: "I'm feeling under the weather today.",
    reference: "امروز حالم خوب نیست.",
    check: "Feeling unwell, not a description of the weather.",
  },
  {
    text: "I'm running late. Please go ahead without me.",
    reference: "دیرم شده. لطفاً بدون من بروید.",
    check: "Being late, not running; preserve both sentences and the request.",
  },
  {
    text: "Let's catch up over coffee tomorrow.",
    reference: "بیا فردا با هم قهوه بخوریم و گپ بزنیم.",
    check: "Meeting to talk tomorrow, not physically catching someone or being above coffee.",
  },
  {
    text: "I might not be able to come at 5; please don't cancel Sara's reservation.",
    reference: "ممکن است نتوانم ساعت ۵ بیایم؛ لطفاً رزرو سارا را لغو نکنید.",
    check: "Preserve uncertainty, both negations, the time, and Sara's name.",
  },
]

function options(args) {
  const result = { endpoint: "http://127.0.0.1:9379/v1", model: "gemma4-e2b", source: "en", target: "fa" }
  for (let index = 0; index < args.length; index++) {
    const key = args[index].replace(/^--/, "")
    if (args[index] === "--help") return { help: true }
    if (!args[index].startsWith("--") || !["endpoint", "model", "source", "target", "text"].includes(key) || args[index + 1] === undefined) {
      throw new Error(`Unknown option or missing value: ${args[index]}. Use --help.`)
    }
    result[key] = args[++index]
  }
  return result
}

async function main() {
  const args = options(process.argv.slice(2))
  if (args.help) {
    console.log("Usage: npm run check:persian -- [--text 'source sentence'] [--source en|fa|fr|...] [--target fa|en|fr|...] [--model gemma4-e2b] [--endpoint http://127.0.0.1:9379/v1]")
    console.log("Compares the previous and current prompts using local Gemma. Default: five English → Persian cases. --text is required for another language pair; select Persian as source or target.")
    return
  }
  const url = new URL(getNormalizedBaseUrl(args.endpoint))
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "[::1]"].includes(url.hostname) || url.username || url.password || url.search || url.hash) {
    throw new Error("Use a local HTTP loopback endpoint for this offline check, e.g. http://127.0.0.1:9379/v1.")
  }
  const source = AVAILABLE_LANGUAGES.find((language) => language.code === args.source)
  const target = AVAILABLE_LANGUAGES.find((language) => language.code === args.target)
  if (!source || !target || source.code === target.code || ![source.code, target.code].includes("fa")) {
    throw new Error("Select two different supported languages, with fa as source or target.")
  }
  if (args.text !== undefined && !args.text.trim()) throw new Error("--text must contain a source sentence.")
  if ((source.code !== "en" || target.code !== "fa") && args.text === undefined) throw new Error("Use --text for a language pair other than English → Persian.")
  const samples = args.text === undefined ? cases : [{ text: args.text }]
  const prompts = [
    ["previous", `Translate from ${source.name} into ${target.name}. Preserve meaning and names. Return only valid JSON: {"translation":"translated text"}. No explanations or Markdown.`],
    ["current", buildTranslationPrompt(source, target)],
  ]
  console.log(`Local model: ${args.model}; ${source.name} → ${target.name}; temperature=0. Linguistic review is manual, not an exact-match score.`)
  for (const sample of samples) {
    const results = []
    for (const [prompt, systemPrompt] of prompts) {
      const result = await translateText(sample.text, { endpointUrl: url.href, useProxy: false, modelName: args.model, systemPrompt })
      results.push({ prompt, translation: result.translation, seconds: Number(result.duration) })
    }
    console.log(JSON.stringify({ ...sample, results }, null, 2))
  }
}

main().catch((error) => {
  console.error(`Persian check failed: ${error.message}. Start the local Pi app first; confirm its model name and port. Use --help for options.`)
  process.exitCode = 1
})
