/**
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */

// Shared language metadata keeps both lanes and both translation directions equal.
export const AVAILABLE_LANGUAGES = [
  { code: "ar", name: "Arabic", voice: "tts", ttsLang: "ar" },
  { code: "en", name: "English", voice: "tts", ttsLang: "en" },
  { code: "es", name: "Spanish", voice: "tts", ttsLang: "es" },
  { code: "ja", name: "Japanese", voice: "tts", ttsLang: "ja" },
  { code: "zh", name: "Chinese", voice: "tts", ttsLang: "zh" },
  { code: "ko", name: "Korean", voice: "tts", ttsLang: "ko" },
  { code: "fa", name: "Persian", voice: "tts", ttsLang: "fa" },
  { code: "ur", name: "Urdu", voice: "tts", ttsLang: "ur" },
  { code: "fr", name: "French", voice: "tts", ttsLang: "fr" },
]

export function textDirection(languageCode) {
  return ["ar", "fa", "ur"].includes(languageCode) ? "rtl" : "ltr"
}

export function buildTranslationPrompt(source, target) {
  // A reported Pi comparison showed the verbose quality prompt taking ~14s versus
  // ~3.5s for the original short prompt. Keep the meaning guidance compact.
  const style = target.code === "fa"
    ? "Use Iranian Persian (Farsi) in Persian script. "
    : ""
  const input = source.code === "fa"
    ? "Read colloquial Persian; resolve clear speech-to-text typos from context, keeping ambiguity. "
    : ""
  return `Translate from ${source.name} into ${target.name} naturally, by meaning and idioms, not word by word. Preserve facts, tone, names, numbers, negation and uncertainty. ${input}${style}Return only valid JSON: {"translation":"translated text"}.`
}
