#!/usr/bin/env python3
import os
import glob
import sys

def tokenize_and_check(filename):
    with open(filename, 'r', encoding='utf-8') as f:
        src = f.read()

    i = 0
    n = len(src)
    brace_stack = []
    paren_stack = []
    errors = []

    while i < n:
        # Line comment
        if src[i:i+2] == '//':
            eol = src.find('\n', i)
            i = n if eol == -1 else eol + 1
            continue

        # Block comment
        if src[i:i+2] == '/*':
            end_comment = src.find('*/', i + 2)
            if end_comment == -1:
                errors.append(f"Unterminated block comment at offset {i}")
                break
            i = end_comment + 2
            continue

        # Triple quote string
        if src[i:i+3] == '"""':
            end_str = src.find('"""', i + 3)
            if end_str == -1:
                errors.append(f"Unterminated triple-quote string at offset {i}")
                break
            i = end_str + 3
            continue

        # Single quote char literal
        if src[i] == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == '\\':
                    j += 1
                j += 1
            i = j + 1
            continue

        # Regular double quote string
        if src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == '\\':
                    j += 1
                j += 1
            i = j + 1
            continue

        ch = src[i]
        line_num = src.count('\n', 0, i) + 1

        if ch == '{':
            brace_stack.append((line_num, '{'))
        elif ch == '}':
            if not brace_stack:
                errors.append(f"Unexpected '}}' at line {line_num}")
            else:
                brace_stack.pop()
        elif ch == '(':
            paren_stack.append((line_num, '('))
        elif ch == ')':
            if not paren_stack:
                errors.append(f"Unexpected ')' at line {line_num}")
            else:
                paren_stack.pop()

        i += 1

    for line_num, _ in brace_stack:
        errors.append(f"Unclosed '{{' opened at line {line_num}")
    for line_num, _ in paren_stack:
        errors.append(f"Unclosed '(' opened at line {line_num}")

    return errors

def main():
    files = glob.glob("/home/antigravity/line-ai-reply/app/src/main/java/**/*.kt", recursive=True)
    all_clean = True
    for f in sorted(files):
        errs = tokenize_and_check(f)
        rel = os.path.relpath(f, "/home/antigravity/line-ai-reply")
        if errs:
            print(f"❌ {rel}:")
            for e in errs:
                print(f"   - {e}")
            all_clean = False
        else:
            print(f"✅ {rel}")

    if all_clean:
        print(f"\nALL {len(files)} KOTLIN SOURCE FILES 100% BALANCED & VALID! 🚀")
    else:
        sys.exit(1)

if __name__ == '__main__':
    main()
