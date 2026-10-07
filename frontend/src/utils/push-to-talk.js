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

// One owner (finger, mouse, or key) holds the mic at a time. Keep the lock
// through async microphone permission and audio encoding, independent of React
// renders, so releasing before permission arrives cannot leave recording on.
export function createPushToTalk({ start, stop, onStart, onReady, onEnd, onAudio, onError }) {
  let active = null

  async function finish(session) {
    if (session.finishing) return
    session.finishing = true
    try {
      const audio = await stop()
      if (!session.cancelled && audio) onAudio(session.context, audio)
    } catch (error) {
      onError?.(error)
    } finally {
      if (active === session) active = null
      onEnd()
    }
  }

  return {
    get busy() { return active !== null },
    press(context, owner) {
      if (active) return false
      const session = { context, owner, ready: false, released: false, cancelled: false }
      active = session
      const failed = (error) => {
        active = null
        onEnd()
        onError?.(error)
      }
      try {
        onStart(context)
        // Invoke start synchronously while the pointer/key gesture is active.
        Promise.resolve(start()).then((ok) => {
          if (!ok) {
            active = null
            onEnd()
            return
          }
          session.ready = true
          if (session.released) finish(session)
          else onReady?.(context)
        }).catch(failed)
      } catch (error) {
        failed(error)
        return false
      }
      return true
    },
    release(owner, cancel = false) {
      const session = active
      if (!session || session.owner !== owner || session.released) return
      session.released = true
      // A press released during permission/setup has captured no useful speech.
      session.cancelled = cancel || !session.ready
      if (session.ready) finish(session)
    },
    cancel() {
      if (!active) return
      active.cancelled = true
      this.release(active.owner, true)
    },
  }
}
