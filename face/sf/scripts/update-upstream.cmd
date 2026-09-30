@echo off
REM Update SeetaFace6 (index umbrella, recursive) and SeetaFace6JNI to the
REM latest upstream, then print the pinned commits for README bookkeeping.
REM Run from the repository root:  face\sf\scripts\update-upstream.cmd
REM
REM After running: rebuild all ABIs, update the commit table in face/sf/README.md,
REM and commit the two gitlink changes (plus .gitmodules if URLs changed).

setlocal
set ROOT=%~dp0..\..\..
set SF6=%ROOT%\face\sf\third_party\SeetaFace6
set JNI=%ROOT%\face\sf\third_party\SeetaFace6JNI

echo === Updating SeetaFace6 index and its nested modules ===
git -C "%SF6%" fetch origin
git -C "%SF6%" checkout master
git -C "%SF6%" pull --ff-only origin master
REM Check out exactly the component commits the new index pins (do NOT use
REM --remote: that would drift away from the official tested combination).
git -C "%SF6%" submodule update --init --recursive

echo === Updating SeetaFace6JNI ===
git -C "%JNI%" fetch origin
git -C "%JNI%" checkout master
git -C "%JNI%" pull --ff-only origin master

echo.
echo === New pinned commits (update face/sf/README.md) ===
git -C "%ROOT%" submodule status --recursive

endlocal
