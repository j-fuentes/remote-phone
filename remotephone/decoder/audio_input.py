"""Capture the desktop's selected recording source for playback on the phone."""

try:
    import sounddevice as sd
except (ImportError, OSError):
    sd = None


class AudioInput:
    """Raw 44.1 kHz stereo PCM capture, deliberately started only by an explicit toggle."""

    def __init__(self, on_data):
        self._on_data = on_data
        self._stream = None

    def start(self):
        if self._stream is not None or sd is None:
            return self._stream is not None
        try:
            self._stream = sd.RawInputStream(samplerate=44100, channels=2, dtype="int16",
                                             blocksize=4096, latency="low", callback=self._callback)
            self._stream.start()
            return True
        except Exception:
            self._stream = None
            return False

    def _callback(self, indata, frames, time, status):
        try:
            self._on_data(bytes(indata))
        except Exception:
            pass

    def stop(self):
        stream, self._stream = self._stream, None
        if stream is not None:
            try:
                stream.abort()
                stream.close()
            except Exception:
                pass
