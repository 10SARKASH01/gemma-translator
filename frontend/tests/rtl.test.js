import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { createRequire } from "node:module"
import { fileURLToPath, pathToFileURL } from "node:url"
import test from "node:test"
import React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { transformWithEsbuild } from "vite"
import { AVAILABLE_LANGUAGES, textDirection } from "../src/utils/languages.js"

test("rendered speech bubbles use their own language direction without changing labels", async () => {
  const componentUrl = new URL("../src/components/ResponseDrawer.jsx", import.meta.url)
  const source = await readFile(componentUrl, "utf8")
  const transformed = await transformWithEsbuild(source, fileURLToPath(componentUrl), {loader: "jsx"})
  // Load the actual JSX component with Vite's transformer; no extra test framework.
  const require = createRequire(import.meta.url)
  const code = transformed.code
    .replace('from "react"', `from ${JSON.stringify(pathToFileURL(require.resolve("react")).href)}`)
    .replace('from "../utils/languages"', `from ${JSON.stringify(new URL("../src/utils/languages.js", import.meta.url).href)}`)
  const {default: ResponseDrawer} = await import(`data:text/javascript;base64,${Buffer.from(code).toString("base64")}`)
  for (const language of AVAILABLE_LANGUAGES) {
    const markup = renderToStaticMarkup(React.createElement(ResponseDrawer, {
      isActive: true, transcriptionSource: "English (Source)", transcriptionText: "Hello",
      transcriptionLang: "en", translationTarget: `${language.name} (Translation)`,
      translationText: "سلام دنیا", translationLang: language.code,
    }))
    assert.match(markup, /class="bubble-text" lang="en" dir="ltr"/)
    assert.ok(markup.includes(`class="bubble-text" lang="${language.code}" dir="${textDirection(language.code)}"`))
    assert.match(markup, /class="bubble-label">English \(Source\)/)
    const reverse = renderToStaticMarkup(React.createElement(ResponseDrawer, {
      isActive: true, transcriptionSource: language.name, transcriptionText: "سلام",
      transcriptionLang: language.code, translationTarget: "English", translationText: "Hello",
      translationLang: "en",
    }))
    assert.ok(reverse.includes(`class="bubble-text" lang="${language.code}" dir="${textDirection(language.code)}"`))
    assert.match(reverse, /class="bubble-text" lang="en" dir="ltr"/)
  }
})
