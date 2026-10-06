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

import React, { useEffect, useRef } from "react"

// One person's lane: the vertical "revolver" language selector plus the
// recording highlight state. Pointer capture keeps push-to-talk held when a
// finger slides off the button; cancelling a gesture discards its recording.
export default function LanguageLane({
  laneId,
  laneLabel,
  languages,
  currentIndex,
  isRecording,
  isPreparing,
  isActivePerson,
  rotationDisabled,
  talkDisabled,
  onRotate,
  onRecordStart,
  onRecordStop,
}) {
  const drumRef = useRef(null)

  // Slide the drum so the current language row is in the viewport.
  // The 24px row height must match .revolver-item in style.css.
  useEffect(() => {
    if (drumRef.current) {
      drumRef.current.style.transform = `translateY(-${currentIndex * 24}px)`
    }
  }, [currentIndex])

  const handlePrev = (e) => {
    e.preventDefault()
    if (!rotationDisabled) onRotate(-1)
  }

  const handleNext = (e) => {
    e.preventDefault()
    if (!rotationDisabled) onRotate(1)
  }

  const handlePointerDown = (e) => {
    if (e.button !== 0 || !e.isPrimary || talkDisabled) return
    e.preventDefault()
    if (onRecordStart(`pointer:${e.pointerId}`)) {
      e.currentTarget.setPointerCapture(e.pointerId)
    }
  }

  const handleTalkKey = (e, pressed) => {
    if (![" ", "Enter"].includes(e.key)) return
    e.preventDefault()
    e.stopPropagation()
    const owner = `button:${laneId}:${e.key}`
    if (pressed) {
      if (!e.repeat && !talkDisabled) onRecordStart(owner)
    } else onRecordStop(owner)
  }

  return (
    <section
      className={`language-lane lane-${laneId === 1 ? "one" : "two"} ${isRecording ? "recording" : ""} ${isActivePerson ? "selected" : ""}`}
      id={`lane-${laneId}`}
    >
      <div className="lane-header">
        <span className="lane-label">PERSON {laneLabel}</span>
      </div>
      <div className="revolver-stage">
        <button
          type="button"
          className="rotator-arrow"
          title="Previous language"
          aria-label={`Previous language for person ${laneLabel}`}
          disabled={rotationDisabled}
          onClick={handlePrev}
        >
          ◀
        </button>
        <div className="revolver-viewport" aria-live="polite" aria-label={languages[currentIndex].name}>
          <div className="revolver-drum" ref={drumRef} aria-hidden="true">
            {languages.map((l, i) => (
              <div key={l.code} className="revolver-item">
                {l.name.split(" ")[0].toUpperCase()}
              </div>
            ))}
          </div>
        </div>
        <button
          type="button"
          className="rotator-arrow"
          title="Next language"
          aria-label={`Next language for person ${laneLabel}`}
          disabled={rotationDisabled}
          onClick={handleNext}
        >
          ▶
        </button>
      </div>
      <span className="language-name" style={{ display: "none" }}>
        {languages[currentIndex].name}
      </span>
      <button
        type="button"
        className="push-to-talk-btn"
        aria-label={`Hold to talk in ${languages[currentIndex].name}, person ${laneLabel}`}
        aria-pressed={isRecording}
        disabled={talkDisabled}
        onPointerDown={handlePointerDown}
        onPointerUp={(e) => onRecordStop(`pointer:${e.pointerId}`)}
        onPointerCancel={(e) => onRecordStop(`pointer:${e.pointerId}`, true)}
        onLostPointerCapture={(e) => onRecordStop(`pointer:${e.pointerId}`, true)}
        onContextMenu={(e) => e.preventDefault()}
        onKeyDown={(e) => handleTalkKey(e, true)}
        onKeyUp={(e) => handleTalkKey(e, false)}
        onBlur={() => {
          onRecordStop(`button:${laneId}: `, true)
          onRecordStop(`button:${laneId}:Enter`, true)
        }}
      >
        {isRecording ? (isPreparing ? "Preparing mic…" : "Release to translate") : "Hold to talk"}
      </button>
      <div className="corner-brackets"></div>
    </section>
  )
}
