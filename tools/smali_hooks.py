"""Narrow, repeatable hooks for supported input-method bytecode."""
import re


def hook_void_entry(source, signature, call):
    """Use original parameter registers before stock bytecode can overwrite them."""
    pattern = re.compile(r'(^\.method[^\n]* ' + re.escape(signature) + r'\n)(.*?)(^\.end method)', re.M | re.S)
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise ValueError('Expected exactly one method: ' + signature)
    match = matches[0]
    body = re.sub(r'^[ \t]*' + re.escape(call.strip()) + r'[ \t]*\n', '', match.group(2), flags=re.M)
    directive = re.compile(r'(^[ \t]*\.(?:locals|registers) \d+[^\n]*\n)', re.M)
    if len(list(directive.finditer(body))) != 1:
        raise ValueError('Expected register declaration: ' + signature)
    body = directive.sub(lambda found: found.group(1) + '    ' + call.strip() + '\n', body, count=1)
    return source[:match.start()] + match.group(1) + body + match.group(3) + source[match.end():]


def hook_void_returns(source, signature, call):
    pattern = re.compile(r'(^\.method[^\n]* ' + re.escape(signature) + r'\n)(.*?)(^\.end method)', re.M | re.S)
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise ValueError('Expected exactly one method: ' + signature)
    match = matches[0]
    body = match.group(2)
    body = re.sub(r'^[ \t]*' + re.escape(call.strip()) + r'[ \t]*\n', '', body, flags=re.M)
    if not re.search(r'^    return-void[ \t]*$', body, re.M):
        raise ValueError('Void method has no normal return: ' + signature)
    body = re.sub(r'^(    return-void)[ \t]*$', '    ' + call.strip() + r'\n\1', body, flags=re.M)
    return source[:match.start()] + match.group(1) + body + match.group(3) + source[match.end():]
