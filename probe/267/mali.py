#!/usr/bin/env python3
"""mali.py <shader dump dir> <out.json>: Mali-G57 and G52 cycles per pixel for every shape program
and path, by #242's method: the whole program's shortest path for pictures and letters, and for
every other path a copy of the program with that path's switches fixed to constants."""
import json, os, re, subprocess, sys, tempfile
MALI = '/srv/ssd1/workspace/composegl/.claude/worktrees/solo-242-tools/Arm_Performance_Studio_2025.3/mali_offline_compiler/malioc'
src, out = sys.argv[1], sys.argv[2]
work = tempfile.mkdtemp(prefix='mali-', dir=os.path.dirname(out))

def compile(text, core):
    path = os.path.join(work, 'x.frag')
    open(path, 'w').write(text)
    r = subprocess.run([MALI, '-c', core, '--format', 'json', path], capture_output=True, text=True)
    d = json.loads(r.stdout)
    v = d['shaders'][0]['variants'][0]
    perf = v['performance']
    pipes = perf['pipelines']
    def bound(key):
        c = dict(zip(pipes, perf[key]['cycle_count']))
        arith = c.get('arith_total', c.get('arithmetic', max([v for k, v in c.items() if k.startswith('arith')] or [0])))
        return max(arith, c.get('load_store', 0), c.get('varying', 0), c.get('texture', 0))
    props = {p['name']: p['value'] for p in v['properties']}
    return {'shortest': bound('shortest_path_cycles'), 'longest': bound('longest_path_cycles'),
            'registers': props['work_registers_used'], 'occupancy': props['thread_occupancy']}

def path_copy(text, kind, mask):
    t = text
    def sub(a, b, need=True):
        nonlocal t
        if a not in t:
            if need: raise SystemExit(f'missing {a!r}')
            return
        t = t.replace(a, b)
    K = {'shape': '0.0', 'shape with border': '0.0', 'shadow': '0.0', 'inner shade': '0.0',
         'gradient': '1.0', 'ramp gradient': '3.0', 'relief': '6.0', 'glyph': '0.0'}[kind]
    sub('float aa = v_shape.z;', 'float aa = 0.0;' if kind == 'glyph' else 'float aa = max(v_shape.z, 0.01);')
    t = t.replace('v_gradient.x', f'({K})')
    if kind != 'shape with border':
        sub('float borderWidth = v_shape.x;', 'float borderWidth = 0.0;')
    if kind == 'shadow':
        sub('float spread = v_shape.y;', 'float spread = max(v_shape.y, 0.01);')
    elif kind == 'inner shade':
        sub('float spread = v_shape.y;', 'float spread = min(v_shape.y, -0.01);')
    else:
        sub('float spread = v_shape.y;', 'float spread = 0.0;')
    if not mask:
        sub('if (u_maskMode > 0.5) {', 'if (false) {', need=False)
    return t

result = {}
for name in sorted(os.listdir(src)):
    text = open(os.path.join(src, name)).read()
    if 'FragColor' not in text and 'gl_FragColor' not in text:
        continue
    if 'float aa = v_shape.z;' not in text:
        if 'gl_Position' in text:
            continue
        for core in ['Mali-G57', 'Mali-G52']:
            result.setdefault('effects', {}).setdefault(core, {})[name] = compile(text, core)
        continue
    tag = 'full' if re.search(r'(?m)^#define CG_FULL', text) else 'held' if re.search(r'(?m)^#define CG_HELD', text) else 'common'
    has_mask = 'u_maskMode' in text
    for core in ['Mali-G57', 'Mali-G52']:
        whole = compile(text, core)
        entry = {'whole': whole, 'paths': {}}
        for kind in ['glyph', 'shape', 'shape with border', 'shadow', 'inner shade', 'gradient', 'ramp gradient', 'relief']:
            for mask in ([False, True] if has_mask else [False]):
                try:
                    c = compile(path_copy(text, kind, mask), core)
                except SystemExit as e:
                    c = {'error': str(e)}
                entry['paths'][kind + ('+mask' if mask else '')] = c
        result.setdefault(tag, {})[core] = entry
        print(tag, core, 'whole', whole, file=sys.stderr)
json.dump(result, open(out, 'w'), indent=1)
