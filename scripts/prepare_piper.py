import json
import sys
from pathlib import Path

import onnx


def load_config(model_path):
    json_path = Path(str(model_path) + ".json")

    if not json_path.exists():
        raise FileNotFoundError(
            f"Missing Piper config: {json_path}"
        )

    with open(
        json_path,
        "r",
        encoding="utf-8"
    ) as f:
        return json.load(f)


def generate_tokens(config, output):

    phoneme_map = config["phoneme_id_map"]

    with open(
        output,
        "w",
        encoding="utf-8"
    ) as f:

        for phoneme, ids in phoneme_map.items():

            if not ids:
                continue

            f.write(
                f"{phoneme} {ids[0]}\n"
            )


def add_metadata(model_path, config):

    model = onnx.load(
        str(model_path),
        load_external_data=True
    )

    metadata = {
        "model_type": "vits",
        "comment": "piper",
        "language": config["language"]["name_english"],
        "voice": config["espeak"]["voice"],
        "has_espeak": "1",
        "n_speakers": str(config["num_speakers"]),
        "sample_rate": str(
            config["audio"]["sample_rate"]
        )
    }

    existing = {
        item.key
        for item in model.metadata_props
    }

    for key, value in metadata.items():

        if key in existing:
            continue

        prop = model.metadata_props.add()
        prop.key = key
        prop.value = str(value)

    onnx.save(
        model,
        str(model_path),
        save_as_external_data=False
    )


def main():

    if len(sys.argv) != 3:

        print(
            "Usage: prepare_piper.py MODEL OUTPUT_DIR"
        )

        sys.exit(1)

    model_path = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])

    output_dir.mkdir(
        parents=True,
        exist_ok=True
    )

    config = load_config(model_path)

    tokens_path =
        output_dir / "tokens.txt"

    generate_tokens(
        config,
        tokens_path
    )

    add_metadata(
        model_path,
        config
    )

    destination =
        output_dir / model_path.name

    if model_path.resolve() != destination.resolve():

        destination.write_bytes(
            model_path.read_bytes()
        )

    print("Piper model prepared successfully")
    print(f"Model: {destination}")
    print(f"Tokens: {tokens_path}")


if __name__ == "__main__":
    main()
