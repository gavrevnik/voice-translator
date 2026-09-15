export type AudioRecorder = {
  start(onLevel?: (level: number) => void): Promise<void>;
  stop(): Promise<Blob>;
  cancel(): Promise<void>;
};

export class BrowserAudioRecorder implements AudioRecorder {
  private stream?: MediaStream;
  private context?: AudioContext;
  private source?: MediaStreamAudioSourceNode;
  private processor?: ScriptProcessorNode;
  private silentGain?: GainNode;
  private chunks: Float32Array[] = [];
  private sourceSampleRate = 48_000;

  async start(onLevel?: (level: number) => void): Promise<void> {
    if (this.stream) throw new Error("A recording is already in progress.");
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error("This browser does not support microphone recording.");
    }

    this.chunks = [];
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    });

    this.context = new AudioContext();
    this.sourceSampleRate = this.context.sampleRate;
    this.source = this.context.createMediaStreamSource(this.stream);
    this.processor = this.context.createScriptProcessor(4096, 1, 1);
    this.silentGain = this.context.createGain();
    this.silentGain.gain.value = 0;

    this.processor.onaudioprocess = (event) => {
      const input = event.inputBuffer.getChannelData(0);
      const copy = new Float32Array(input);
      this.chunks.push(copy);

      if (onLevel) {
        let sum = 0;
        for (let index = 0; index < copy.length; index += 1) sum += copy[index] ** 2;
        onLevel(Math.min(1, Math.sqrt(sum / copy.length) * 5));
      }
    };

    this.source.connect(this.processor);
    this.processor.connect(this.silentGain);
    this.silentGain.connect(this.context.destination);
  }

  async stop(): Promise<Blob> {
    if (!this.stream || !this.context) throw new Error("No recording is in progress.");

    const samples = mergeSamples(this.chunks);
    const sampleRate = this.sourceSampleRate;
    await this.release();

    if (samples.length === 0) throw new Error("The recording is empty.");
    const downsampled = downsample(samples, sampleRate, 16_000);
    return encodeWav(downsampled, 16_000);
  }

  async cancel(): Promise<void> {
    this.chunks = [];
    await this.release();
  }

  private async release(): Promise<void> {
    this.processor?.disconnect();
    this.source?.disconnect();
    this.silentGain?.disconnect();
    this.stream?.getTracks().forEach((track) => track.stop());
    await this.context?.close();
    this.stream = undefined;
    this.context = undefined;
    this.source = undefined;
    this.processor = undefined;
    this.silentGain = undefined;
  }
}

export function mergeSamples(chunks: Float32Array[]): Float32Array {
  const result = new Float32Array(chunks.reduce((total, chunk) => total + chunk.length, 0));
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

export function downsample(
  source: Float32Array,
  sourceRate: number,
  targetRate: number,
): Float32Array {
  if (targetRate >= sourceRate) return source;
  const ratio = sourceRate / targetRate;
  const result = new Float32Array(Math.round(source.length / ratio));

  for (let outputIndex = 0; outputIndex < result.length; outputIndex += 1) {
    const start = Math.round(outputIndex * ratio);
    const end = Math.min(source.length, Math.round((outputIndex + 1) * ratio));
    let sum = 0;
    for (let sourceIndex = start; sourceIndex < end; sourceIndex += 1) {
      sum += source[sourceIndex];
    }
    result[outputIndex] = sum / Math.max(1, end - start);
  }
  return result;
}

export function encodeWav(samples: Float32Array, sampleRate: number): Blob {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);

  writeAscii(view, 0, "RIFF");
  view.setUint32(4, 36 + samples.length * 2, true);
  writeAscii(view, 8, "WAVE");
  writeAscii(view, 12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeAscii(view, 36, "data");
  view.setUint32(40, samples.length * 2, true);

  for (let index = 0; index < samples.length; index += 1) {
    const sample = Math.max(-1, Math.min(1, samples[index]));
    view.setInt16(44 + index * 2, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
  }

  return new Blob([buffer], { type: "audio/wav" });
}

function writeAscii(view: DataView, offset: number, value: string): void {
  for (let index = 0; index < value.length; index += 1) {
    view.setUint8(offset + index, value.charCodeAt(index));
  }
}
