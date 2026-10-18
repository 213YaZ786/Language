#!/bin/sh
# The recognition service driven as any app drives it (debug RecognizerTestActivity, SpeechRecognizer), the way
# AOSP's CTS checks a recognition service's callbacks (AbstractRecognitionServiceTest)
# https://android.googlesource.com/platform/cts/+/refs/heads/main/tests/tests/voiceRecognition/src/android/voicerecognition/cts/AbstractRecognitionServiceTest.java
# Needs: the debug build installed, RECORD_AUDIO granted, the Base model in files/speech and chosen,
# whisper.cpp's samples/jfk.wav in files/. Usage: maestro/run-recognizer.sh [adb serial]
set -e
D="adb ${1:+-s $1}"
P=com.yaz.voice.debug
T=$P/com.yaz.voice.recognition.RecognizerTestActivity

case_run() { # wait seconds, expected line, extras...
  wait=$1; want=$2; shift 2
  $D shell am force-stop $P
  $D shell am start -n $T "$@" >/dev/null
  sleep "$wait"
  if $D shell run-as $P cat files/recognize-test.txt | grep -q "$want"; then echo "ok   $want ($*)"; else echo "FAIL $want ($*)"; fi
}

case_run 6 "error 3" --es wav jfk.wav --ei encoding 4   # an encoding it cannot read: ERROR_AUDIO
case_run 6 "error 6" --es wav nothing.wav               # nothing to hear: ERROR_SPEECH_TIMEOUT
case_run 15 "error 6" --es lang en                      # the microphone, nothing said
case_run 240 "results And so my fellow Americans" --es wav jfk.wav --es lang en --ez detect true
