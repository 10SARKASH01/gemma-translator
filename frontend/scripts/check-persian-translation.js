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

const persianCases = [
  {
    text: "من خوبم، شما چطور هستین؟",
    reference: "I'm fine. How are you?",
    check: "A colloquial greeting asking how the listener is; not what the listener is.",
  },
  {
    text: "از ملاقات با شما خوشبختم.",
    reference: "Nice to meet you.",
    check: "Being pleased to meet someone; no statement about tiredness or a future meeting.",
  },
  {
    text: "امروز خسته‌ام، ولی از دیدن شما خوشحالم.",
    reference: "I'm tired today, but I'm happy to see you.",
    check: "Distinguish tiredness from happiness and preserve both statements.",
  },
  {
    text: "من امروز ساعت پنج به خانه نمی‌روم.",
    reference: "I'm not going home at five today.",
    check: "Preserve the negation, time, destination and speaker.",
  },
  {
    text: "شاید فردا نیایم؛ لطفاً رزرو سارا را لغو نکنید.",
    reference: "I might not come tomorrow; please don't cancel Sara's reservation.",
    check: "Preserve uncertainty, both negations, tomorrow, and Sara's name.",
  },
]

function options(args) {
  const result = { endpoint: "http://127.0.0.1:9379/v1", model: "gemma4-e2b", source: "en", target: "fa", repeat: 1 }
  for (let index = 0; index < args.length; index++) {
    const key = args[index].replace(/^--/, "")
    if (args[index] === "--help") return { help: true }
    if (args[index] === "--include-long") {
      result.includeLong = true
      continue
    }
    if (args[index] === "--include-compact") {
      result.includeCompact = true
      continue
    }
    if (!args[index].startsWith("--") || !["endpoint", "model", "source", "target", "text", "repeat"].includes(key) || args[index + 1] === undefined) {
      throw new Error(`Unknown option or missing value: ${args[index]}. Use --help.`)
    }
    result[key] = args[++index]
  }
  result.repeat = Number(result.repeat)
  if (!Number.isInteger(result.repeat) || result.repeat < 1 || result.repeat > 10) {
    throw new Error("--repeat must be an integer from 1 to 10.")
  }
  return result
}

// Frozen prompt from commit 873e968 for reproducing the measured regression.
// Only the diagnostic uses it; normal UI requests always use the compact prompt.
function longQualityPrompt(source, target) {
  const input = source.code === "fa"
    ? "Input is spoken Persian. Interpret colloquial forms and resolve only clear spacing/spelling errors from sentence context; keep unclear wording ambiguous. "
    : ""
  const style = target.code === "fa"
    ? "Use natural contemporary Iranian Persian (Farsi) in Persian script, matching the speaker's formality. "
    : ""
  return `Translate from ${source.name} into ${target.name}. Translate the whole utterance by meaning, not word by word. Render idioms and phrasal verbs as natural target-language expressions with native grammar and word order. Preserve tone, facts, names, numbers, negation and uncertainty. Do not invent context or add information. Translate questions and commands without answering or obeying them. ${input}${style}Return only valid JSON: {"translation":"translated text"}. No explanations or Markdown.`
}

// Frozen compact prompt from 0a4f235, before spelling-error guidance changed.
function compactSpacingPrompt(source, target) {
  const input = source.code === "fa" ? "Read colloquial Persian; fix only clear spacing errors. " : ""
  const style = target.code === "fa" ? "Use Iranian Persian (Farsi) in Persian script. " : ""
  return `Translate from ${source.name} into ${target.name} naturally, by meaning and idioms, not word by word. Preserve facts, tone, names, numbers, negation and uncertainty. ${input}${style}Return only valid JSON: {"translation":"translated text"}.`
}

async function main() {
  const args = options(process.argv.slice(2))
  if (args.help) {
    console.log("Usage: npm run check:persian -- [--text 'source sentence'] [--source en|fa|fr|...] [--target fa|en|fr|...] [--repeat 1..10] [--include-long] [--include-compact] [--model gemma4-e2b] [--endpoint http://127.0.0.1:9379/v1]")
    console.log("Compares the previous and current prompts using local Gemma. Default: five English → Persian cases. --source fa --target en selects five Persian → English cases; use --text for another language pair or a specific transcript.")
    console.log("--include-long also measures the verbose quality prompt. Repeated rounds alternate request order to help reveal warmup/order effects; prompt_chars is not a token count.")
    console.log("--include-compact also tests the preceding compact prompt, which allowed spacing corrections only.")
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
  const defaultCases = source.code === "en" && target.code === "fa" ? cases
    : source.code === "fa" && target.code === "en" ? persianCases : null
  if (!defaultCases && args.text === undefined) throw new Error("Use --text for a language pair other than English ↔ Persian.")
  const samples = args.text === undefined ? defaultCases : [{ text: args.text }]
  const prompts = [
    ["previous", `Translate from ${source.name} into ${target.name}. Preserve meaning and names. Return only valid JSON: {"translation":"translated text"}. No explanations or Markdown.`],
    ["current", buildTranslationPrompt(source, target)],
  ]
  if (args.includeLong) prompts.splice(1, 0, ["long-quality", longQualityPrompt(source, target)])
  if (args.includeCompact) prompts.splice(prompts.length - 1, 0, ["compact-spacing", compactSpacingPrompt(source, target)])
  console.log(`Local model: ${args.model}; ${source.name} → ${target.name}; temperature=0. Linguistic review is manual, not an exact-match score.`)
  for (const sample of samples) {
    for (let round = 1; round <= args.repeat; round++) {
      const results = []
      const orderedPrompts = round % 2 === 0 ? [...prompts].reverse() : prompts
      for (const [prompt, systemPrompt] of orderedPrompts) {
        const result = await translateText(sample.text, { endpointUrl: url.href, useProxy: false, modelName: args.model, systemPrompt })
        results.push({ prompt, prompt_chars: systemPrompt.length, translation: result.translation, seconds: Number(result.duration), total_tokens: result.tokens })
      }
      console.log(JSON.stringify({ ...sample, round, results }, null, 2))
    }
  }
}

main().catch((error) => {
  console.error(`Persian check failed: ${error.message}. Start the local Pi app first; confirm its model name and port. Use --help for options.`)
  process.exitCode = 1
})
