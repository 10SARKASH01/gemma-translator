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

import React, { useState, useEffect, useRef, useCallback } from "react"
import LanguageLane from "./components/LanguageLane"
import ResponseDrawer from "./components/ResponseDrawer"
import Visualizer from "./components/Visualizer"
import { useAudioRecorder } from "./hooks/useAudioRecorder"
import {
  transcribeAudio,
  translateText,
  fetchSpeechAudio,
} from "./utils/api"
import { playBlip } from "./utils/audio-blip"
import { AVAILABLE_LANGUAGES, buildTranslationPrompt } from "./utils/languages"
import { createSpeechPlayer } from "./utils/speech-player"
import { createPushToTalk } from "./utils/push-to-talk"

// Core orchestrator for the two-person kiosk translator.
// Flow: hold a key or touch button → record mic → POST /api/stt (local STT)
// → LLM translation via /proxy (Gemma, strict-JSON prompt) → /api/tts playback.

function TranslatorApp({ config, controlsDisabled = false }) {
  // UI State
  const [isDrawerOpen, setIsDrawerOpen] = useState(false)
  const [activePerson, setActivePerson] = useState(1)

  // Translation State
  const [transcriptionData, setTranscriptionData] = useState({
    source: "",
    lang: "",
    text: "— listening —",
  })
  const [translationData, setTranslationData] = useState({
    target: "",
    lang: "",
    text: "— waiting —",
  })
  const [metaText, setMetaText] = useState("")

  const speechPlayerRef = useRef(null)
  const translationSessionRef = useRef(0)
  const pushToTalkRef = useRef(null)
  const recordingHandlersRef = useRef(null)

  // Language Lanes State
  const [lang1Index, setLang1Index] = useState(0)
  const [lang2Index, setLang2Index] = useState(1)
  const [activeLaneRecording, setActiveLaneRecording] = useState(null) // 1 or 2

  const { isRecording, startRecording, stopRecording, analyser, micError } =
    useAudioRecorder()

  useEffect(() => {
    if (micError) {
      setIsDrawerOpen(true)
      setTranscriptionData({ source: "Microphone", text: "Access Failed" })
      setTranslationData({
        target: "Error",
        text: `${micError} (HTTPS is required when accessing from remote devices)`,
      })
    }
  }, [micError])

  const stopSpeaking = useCallback(() => {
    speechPlayerRef.current?.stop()
  }, [])

  useEffect(() => () => {
    translationSessionRef.current++
    pushToTalkRef.current?.cancel()
    stopSpeaking()
  }, [stopSpeaking])

  // Begin with a shorter phrase and prepare one following chunk during playback.
  const playTTS = useCallback(
    (text, targetLang, onFirstAudio) => {
      if (!speechPlayerRef.current) {
        speechPlayerRef.current = createSpeechPlayer({ fetchAudio: fetchSpeechAudio })
      }
      speechPlayerRef.current.play(text, targetLang, {
        onFirstAudio,
        onError: (error) => alert(`Speech output (${targetLang}): ${error.message}`),
      })
    },
    [],
  )

  // Rotate a lane's language, skipping the slot held by the other lane
  // (the two lanes may never show the same language).
  const handleRotateLanguage = useCallback(
    (lane, direction) => {
      if (controlsDisabled || pushToTalkRef.current?.busy) return
      const N = AVAILABLE_LANGUAGES.length

      playBlip("language")

      if (lane === 1) {
        let ni = (lang1Index + direction + N) % N
        if (ni === lang2Index) ni = (ni + direction + N) % N
        setLang1Index(ni)
      } else {
        let ni = (lang2Index + direction + N) % N
        if (ni === lang1Index) ni = (ni + direction + N) % N
        setLang2Index(ni)
      }
    },
    [lang1Index, lang2Index, controlsDisabled],
  )

  // Translation Pipeline
  const processTranslation = async ({ src, dst }, base64Data) => {
    const session = ++translationSessionRef.current
    setIsDrawerOpen(true)

    setTranscriptionData({
      source: `${src.name} (Source)`,
      lang: src.code,
      text: "Analyzing voice input...",
    })
    setTranslationData({
      target: `${dst.name} (Translation)`,
      lang: dst.code,
      text: "Translating...",
    })
    setMetaText("")

    try {
      // 1. Transcription
      setTranscriptionData((prev) => ({ ...prev, text: "Listening..." }))
      const sttStarted = performance.now()
      const transcribedText = await transcribeAudio(base64Data, src.code)
      if (session !== translationSessionRef.current) return
      const sttDuration = ((performance.now() - sttStarted) / 1000).toFixed(2)
      setMetaText(`STT: ${sttDuration}s`)
      setTranscriptionData((prev) => ({ ...prev, text: transcribedText }))

      if (!transcribedText.trim()) {
        setTranslationData((prev) => ({
          ...prev,
          text: "(No speech detected)",
        }))
        return
      }

      // 2. Translation
      const result = await translateText(transcribedText, {
        ...config,
        modelName: config.modelName,
        systemPrompt: buildTranslationPrompt(src, dst),
      })
      if (session !== translationSessionRef.current) return

      setTranslationData((prev) => ({ ...prev, text: result.translation }))
      const timings = `STT: ${sttDuration}s | Gemma: ${result.duration}s`
      const tokens = result.tokens == null ? "" : ` | Tokens: ${result.tokens}`
      setMetaText(`${timings}${tokens}`)

      if (config.enableTts) {
        playTTS(result.translation, dst.ttsLang, (milliseconds) => {
          if (session !== translationSessionRef.current) return
          setMetaText(`${timings} | Speech ready: ${(milliseconds / 1000).toFixed(2)}s${tokens}`)
        })
      }
    } catch (err) {
      if (session !== translationSessionRef.current) return
      console.error(err)
      setTranscriptionData((prev) => ({
        ...prev,
        text: prev.text === "Listening..." ? "(Transcription failed)" : prev.text,
      }))
      setTranslationData((prev) => ({ ...prev, text: `Error: ${err.message}` }))
    }
  }

  // Share one mic owner across touch, mouse, and keyboard. Snapshot languages
  // at press time; lock rotation even while microphone permission is pending.
  recordingHandlersRef.current = {
    startRecording,
    stopRecording,
    onStart: ({ lane }) => {
      translationSessionRef.current++
      stopSpeaking()
      if (activePerson !== lane) playBlip("speaker")
      setActivePerson(lane)
      setActiveLaneRecording(lane)
      playBlip("ping")
    },
    onAudio: (context, audio) => processTranslation(context, audio.base64Data),
  }
  if (!pushToTalkRef.current) {
    pushToTalkRef.current = createPushToTalk({
      start: () => recordingHandlersRef.current.startRecording(),
      stop: () => recordingHandlersRef.current.stopRecording(),
      onStart: (context) => recordingHandlersRef.current.onStart(context),
      onEnd: () => setActiveLaneRecording(null),
      onAudio: (context, audio) => recordingHandlersRef.current.onAudio(context, audio),
      onError: (error) => {
        setIsDrawerOpen(true)
        setTranslationData({ target: "Microphone", text: `Error: ${error.message}` })
      },
    })
  }

  const handleRecordStart = useCallback((lane, owner) => {
    if (controlsDisabled) return false
    return pushToTalkRef.current.press({
      lane,
      src: AVAILABLE_LANGUAGES[lane === 1 ? lang1Index : lang2Index],
      dst: AVAILABLE_LANGUAGES[lane === 1 ? lang2Index : lang1Index],
    }, owner)
  }, [controlsDisabled, lang1Index, lang2Index])

  const handleRecordStop = useCallback((owner, cancel = false) => {
    pushToTalkRef.current.release(owner, cancel)
  }, [])

  useEffect(() => {
    if (controlsDisabled) pushToTalkRef.current.cancel()
  }, [controlsDisabled])

  useEffect(() => {
    const cancel = () => pushToTalkRef.current.cancel()
    const onVisibility = () => { if (document.hidden) cancel() }
    window.addEventListener("blur", cancel)
    document.addEventListener("visibilitychange", onVisibility)
    return () => {
      window.removeEventListener("blur", cancel)
      document.removeEventListener("visibilitychange", onVisibility)
    }
  }, [])

  // Push-to-talk keyboard control (two modes, see README):
  // landscape = one "active person" driven by Space/Z/arrows;
  // vertical   = independent per-lane keys (Z/X for record, arrows and -/+).
  // keydown starts recording, keyup stops — e.repeat guards auto-repeat.
  useEffect(() => {
    const handleKeyDown = (e) => {
      if (controlsDisabled) return
      if (["INPUT", "TEXTAREA", "SELECT"].includes(e.target.tagName)) return
      const key = e.key.toLowerCase()
      if (e.target.closest("button") && [" ", "enter"].includes(key)) return

      if (config.keyboardMode === "landscape") {
        if (key === " " || e.key === "Spacebar") {
          e.preventDefault()
          if (!e.repeat && !pushToTalkRef.current.busy) {
            playBlip("speaker")
            setActivePerson((p) => (p === 1 ? 2 : 1))
          }
        } else if (key === "z") {
          e.preventDefault()
          if (!e.repeat) handleRecordStart(activePerson, "keyboard:z")
        } else if (e.key === "ArrowLeft") {
          e.preventDefault()
          handleRotateLanguage(activePerson, -1)
        } else if (e.key === "ArrowRight") {
          e.preventDefault()
          handleRotateLanguage(activePerson, 1)
        }
      } else {
        if (key === "z") {
          e.preventDefault()
          if (!e.repeat) handleRecordStart(1, "keyboard:z")
        } else if (key === "x") {
          e.preventDefault()
          if (!e.repeat) handleRecordStart(2, "keyboard:x")
        } else if (e.key === "ArrowLeft") {
          e.preventDefault()
          handleRotateLanguage(1, -1)
        } else if (e.key === "ArrowRight") {
          e.preventDefault()
          handleRotateLanguage(1, 1)
        } else if (key === "-" || key === "_") {
          e.preventDefault()
          handleRotateLanguage(2, -1)
        } else if (key === "+" || key === "=") {
          e.preventDefault()
          handleRotateLanguage(2, 1)
        }
      }
    }

    const handleKeyUp = (e) => {
      const key = e.key.toLowerCase()
      if (key === "z" || key === "x") handleRecordStop(`keyboard:${key}`)
    }

    window.addEventListener("keydown", handleKeyDown)
    window.addEventListener("keyup", handleKeyUp)
    return () => {
      window.removeEventListener("keydown", handleKeyDown)
      window.removeEventListener("keyup", handleKeyUp)
    }
  }, [
    config.keyboardMode,
    controlsDisabled,
    activePerson,
    handleRecordStart,
    handleRecordStop,
    handleRotateLanguage,
  ])

  return (
    <div className="translator-envelope">
      <ResponseDrawer
        isActive={isDrawerOpen}
        onClose={() => setIsDrawerOpen(false)}
        transcriptionSource={transcriptionData.source}
        transcriptionText={transcriptionData.text}
        transcriptionLang={transcriptionData.lang}
        translationTarget={translationData.target}
        translationText={translationData.text}
        translationLang={translationData.lang}
        metaText={metaText}
      />

      <main className="translator-workspace">
        <div className="languages-container">
          <LanguageLane
            laneId={1}
            laneLabel="1"
            languages={AVAILABLE_LANGUAGES}
            currentIndex={lang1Index}
            isRecording={activeLaneRecording === 1}
            isPreparing={activeLaneRecording === 1 && !isRecording}
            rotationDisabled={controlsDisabled || activeLaneRecording !== null}
            talkDisabled={controlsDisabled || activeLaneRecording === 2}
            isActivePerson={
              config.keyboardMode === "landscape" && activePerson === 1
            }
            onRotate={(dir) => handleRotateLanguage(1, dir)}
            onRecordStart={(owner) => handleRecordStart(1, owner)}
            onRecordStop={handleRecordStop}
          />
          <LanguageLane
            laneId={2}
            laneLabel="2"
            languages={AVAILABLE_LANGUAGES}
            currentIndex={lang2Index}
            isRecording={activeLaneRecording === 2}
            isPreparing={activeLaneRecording === 2 && !isRecording}
            rotationDisabled={controlsDisabled || activeLaneRecording !== null}
            talkDisabled={controlsDisabled || activeLaneRecording === 1}
            isActivePerson={
              config.keyboardMode === "landscape" && activePerson === 2
            }
            onRotate={(dir) => handleRotateLanguage(2, dir)}
            onRecordStart={(owner) => handleRecordStart(2, owner)}
            onRecordStop={handleRecordStop}
          />
        </div>

        <Visualizer
          activePerson={activePerson}
          isRecording={isRecording}
          analyser={analyser}
          barsCount={parseInt(config.visualizerBars, 10)}
        />
      </main>
    </div>
  )
}

export default TranslatorApp
