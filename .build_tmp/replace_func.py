"""Robust line-range replacement for CameraPermissionOnboarding.

Finds the function's annotation (`@Composable`), the line right before
`fun CameraPermissionOnboarding(`, the opening brace `{` of the function
body, and the matching closing brace. Replaces that exact span with the
contents of C:/tmp/cameraui_new_func.kt, which already contains the full
new function (header, body, KDoc, and the helper FeatureHighlight).

Everything outside the function is untouched, so CameraActiveScreen,
PhotoViewerOverlay, SettingsScreen, settings strings, etc. all stay
byte-identical to git HEAD.
"""
import sys

SRC = 'app/src/main/java/com/example/CameraUi.kt'
NEW = 'C:/tmp/cameraui_new_func.kt'


def main() -> int:
    with open(SRC, 'r', encoding='utf-8') as f:
        content = f.read()

    # Match the KDoc/@Composable immediately before the function. We
    # find the start of `fun CameraPermissionOnboarding(` and walk
    # backwards past whitespace to capture the full annotation.
    func_pos = content.find('fun CameraPermissionOnboarding(')
    if func_pos == -1:
        print('ERROR: could not locate fun CameraPermissionOnboarding(', file=sys.stderr)
        return 1

    # Walk back to the start of the line containing the annotation.
    # We want to keep "@Composable" + any preceding blank line block.
    start = content.rfind('\n@Composable', 0, func_pos)
    if start == -1:
        # Maybe a single @OptIn before @Composable. Try wider scan.
        start = content.rfind('\n@OptIn', 0, func_pos)
        if start == -1:
            # Fall back to the line containing the keyword.
            line_start = content.rfind('\n', 0, func_pos) + 1
            start = line_start - 1  # include the leading newline
        else:
            start += 1  # skip the leading newline so we replace exactly the line
    else:
        start += 1  # skip leading newline

    # Find the body opening brace AFTER the function header closes.
    # We need to skip past the parameter list, so we don't accidentally
    # match a brace inside a default-value expression.
    paren_close = content.find(')', func_pos)
    if paren_close == -1:
        print('ERROR: could not find ) after fun CameraPermissionOnboarding(', file=sys.stderr)
        return 2
    body_open = content.find('{', paren_close)
    if body_open == -1:
        print('ERROR: could not find opening { of function body', file=sys.stderr)
        return 3

    # Walk the file forward counting braces to find the matching close.
    depth = 0
    i = body_open
    body_close = -1
    while i < len(content):
        c = content[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                body_close = i
                break
        i += 1
    if body_close == -1:
        print('ERROR: could not find matching } of function body', file=sys.stderr)
        return 4

    with open(NEW, 'r', encoding='utf-8') as f:
        new_content = f.read()

    # Splice. We include a trailing \n\n so the next function (FeatureHighlight
    # already lives inside new_content) sits cleanly with a blank before
    # CameraActiveScreen.
    new_content_with_pad = new_content.rstrip() + '\n\n'
    new_file = content[:start] + new_content_with_pad + content[body_close + 1:]

    with open(SRC, 'w', encoding='utf-8') as f:
        f.write(new_file)

    print(f'OK: replaced bytes [{start}..{body_close}] ({body_close - start + 1} bytes) with {len(new_content_with_pad)} bytes from {NEW}.')
    print(f'function header preamble begins at offset {start}, ends at offset {body_close}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
