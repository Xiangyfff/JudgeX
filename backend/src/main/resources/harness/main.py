import json
import sys

#__SOLUTION__


def __judgex_fmt(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(__judgex_fmt(item) for item in value) + "]"
    return str(value)


def __judgex_main():
    raw = sys.stdin.read().strip()
    args = json.loads("[" + raw + "]") if raw else []
    result = getattr(Solution(), "//__METHOD__")(*args)
    if result is not None:
        print(__judgex_fmt(result))


if __name__ == "__main__":
    __judgex_main()
