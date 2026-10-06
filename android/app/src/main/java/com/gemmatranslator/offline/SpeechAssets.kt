package com.gemmatranslator.offline

/** Pinned upstream archives. Downloads happen only in setup, never during inference. */
object SpeechAssets {
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    val STT = AssetSpec(
        id = "whisper-small-int8",
        title = "Multilingual Whisper small (INT8)",
        url = "$RELEASES/asr-models/sherpa-onnx-whisper-small.tar.bz2",
        sha256 = "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6",
        bytes = 639_387_718L,
        archive = true,
        requiredFiles = listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"),
    )

    val SUPERTONIC = AssetSpec(
        id = "supertonic-3-int8",
        title = "Supertonic 3 neural voices (Arabic, English, Spanish, Japanese, Korean, French)",
        url = "$RELEASES/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
        sha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
        bytes = 128_774_318L,
        archive = true,
        requiredFiles = listOf(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin", "LICENSE",
        ),
    )

    val CHINESE = AssetSpec(
        id = "kokoro-zh-int8",
        title = "Chinese neural voice (Kokoro Xiaoxiao)",
        url = "$RELEASES/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        sha256 = "4c3052abaa60943a341f193888cf6abd68787dae6ab8ae5c925a706caa247e4e",
        bytes = 132_303_094L,
        archive = true,
        requiredFiles = listOf(
            "model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-zh.txt", "number-zh.fst", "espeak-ng-data",
            "espeak-ng-data/phontab", "espeak-ng-data/phonindex", "espeak-ng-data/phondata",
            "espeak-ng-data/cmn_dict", "espeak-ng-data/lang/sit/cmn", "LICENSE",
        ),
    )

    val PERSIAN = AssetSpec(
        id = "piper-fa-amir",
        title = "Persian neural voice (Piper Amir)",
        url = "$RELEASES/tts-models/vits-piper-fa_IR-amir-medium.tar.bz2",
        sha256 = "46af65c1cdd86ec300db3998f2bf31a68d9e38da930c0655c1ff6f610e84f864",
        bytes = 67_180_373L,
        archive = true,
        requiredFiles = listOf(
            "fa_IR-amir-medium.onnx", "fa_IR-amir-medium.onnx.json", "tokens.txt", "MODEL_CARD", "espeak-ng-data",
            "espeak-ng-data/phontab", "espeak-ng-data/phonindex", "espeak-ng-data/phondata",
            "espeak-ng-data/fa_dict", "espeak-ng-data/lang/ira/fa",
        ),
    )

    val URDU = AssetSpec(
        id = "piper-ur-fasih",
        title = "Urdu neural voice (Piper Fasih)",
        url = "$RELEASES/tts-models/vits-piper-ur_PK-fasih-medium.tar.bz2",
        sha256 = "de64e03109f3c896b7cf910db6d9ffd2703e3d665580d9a19bedbf3e0760d0e4",
        bytes = 67_227_718L,
        archive = true,
        requiredFiles = listOf(
            "ur_PK-fasih-medium.onnx", "ur_PK-fasih-medium.onnx.json", "tokens.txt", "MODEL_CARD", "espeak-ng-data",
            "espeak-ng-data/phontab", "espeak-ng-data/phonindex", "espeak-ng-data/phondata",
            "espeak-ng-data/ur_dict", "espeak-ng-data/lang/inc/ur",
        ),
    )

    val VOICES: Map<String, AssetSpec> = linkedMapOf(
        "ar" to SUPERTONIC, "en" to SUPERTONIC, "es" to SUPERTONIC,
        "ja" to SUPERTONIC, "zh" to CHINESE, "ko" to SUPERTONIC,
        "fa" to PERSIAN, "ur" to URDU, "fr" to SUPERTONIC,
    )

    val ALL: List<AssetSpec> = (listOf(STT) + VOICES.values).distinctBy { it.id }

    fun voice(languageCode: String): AssetSpec = VOICES[languageCode]
        ?: throw IllegalArgumentException("Unsupported speech language: $languageCode")
}
