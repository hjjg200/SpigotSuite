#!/usr/bin/python3
import json
import sys
from pathlib import Path

def print_usage():
    sys.exit("Usage: build_advancement_json.py <en.json> <other_lang.json> >> out.json")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print_usage()
    
    en_path = Path(sys.argv[1])
    lc_path = Path(sys.argv[2])

    if en_path.suffix != '.json' or lc_path.suffix != '.json':
        print_usage()

    en_f = open(en_path,)
    lc_f = open(lc_path,)

    en_data = json.load(en_f)
    lc_data = json.load(lc_f)

    out_data = dict()

    ## Fixed
    fmt_keys = [
        "chat.type.advancement.task",
        "chat.type.advancement.challenge",
        "chat.type.advancement.goal"
    ]
    for k in fmt_keys:
        out_data[k] = lc_data[k]

    ## Advancements
    for k in en_data:
        if not k.startswith("advancements"):
            continue
        if not k.endswith(".title"):
            continue
        
        adv_title = en_data[k]
        base_key = k[:-6]
        desc_key = base_key + ".description"

        obj = {'title': lc_data[k], 'description': en_data[desc_key]}
        out_data[adv_title] = obj
        if desc_key in lc_data:
            out_data[adv_title]['description'] = lc_data[desc_key]

    en_f.close()
    lc_f.close()

    print(json.dumps(out_data, indent=2))