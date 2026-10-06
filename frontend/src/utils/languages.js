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
]

export function textDirection(languageCode) {
  return ["ar", "fa", "ur"].includes(languageCode) ? "rtl" : "ltr"
}

export function buildTranslationPrompt(source, target) {
  return `You are a high-performance translator. Your task is to translate text from ${source.name} into ${target.name}.\nYou MUST format your response as a valid JSON object matching this structure:\n{\n  "translation": "High-quality, natural translation into ${target.name}"\n}\nDo NOT return anything else except this JSON object. No Markdown block wraps (no \`\`\`json), no introductory text, no conversational text. Start directly with "{" and end directly with "}".`
}
