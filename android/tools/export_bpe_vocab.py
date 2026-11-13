"""Export a SentencePiece ``bpe.model`` to the ``bpe.vocab`` file sherpa-onnx needs for hotwords.

sherpa-onnx encodes hotwords for BPE transducer models with a ``bpe.vocab`` file
(``piece<TAB>score`` per line). The streaming Zipformer FlowKey bundles
(sherpa-onnx-streaming-zipformer-en-2023-06-26) ships only ``bpe.model``, so the vocab
committed at ``app/src/main/assets/hotwords/en-2023-06-26.bpe.vocab`` was generated with this
script. It has no dependencies: it reads the SentencePiece protobuf directly.

Usage:
    python tools/export_bpe_vocab.py path/to/bpe.model out/bpe.vocab

``fetch-deps.sh --model`` keeps ``bpe.model`` in its temp dir; regenerating is only needed if
you replace the bundled streaming model with another BPE model.
"""
import struct
import sys


def _varint(buf: bytes, i: int):
    result = shift = 0
    while True:
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return result, i


def _fields(buf: bytes):
    """Yields (field number, wire type, value) for one protobuf message."""
    i = 0
    while i < len(buf):
        key, i = _varint(buf, i)
        field, wire_type = key >> 3, key & 7
        if wire_type == 0:
            value, i = _varint(buf, i)
        elif wire_type == 1:
            value, i = buf[i:i + 8], i + 8
        elif wire_type == 2:
            length, i = _varint(buf, i)
            value, i = buf[i:i + length], i + length
        elif wire_type == 5:
            value, i = buf[i:i + 4], i + 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire_type}")
        yield field, wire_type, value


def export(model_path: str, vocab_path: str) -> int:
    """ModelProto field 1 = repeated SentencePiece {1: piece (string), 2: score (float)}."""
    data = open(model_path, "rb").read()
    lines = []
    for field, wire_type, value in _fields(data):
        if field != 1 or wire_type != 2:
            continue
        piece, score = "", 0.0
        for f2, _, v2 in _fields(value):
            if f2 == 1:
                piece = v2.decode("utf-8")
            elif f2 == 2:
                score = struct.unpack("<f", v2)[0]
        lines.append(f"{piece}\t{score}\n")
    with open(vocab_path, "w", encoding="utf-8", newline="\n") as out:
        out.writelines(lines)
    return len(lines)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    print(export(sys.argv[1], sys.argv[2]), "pieces written")
