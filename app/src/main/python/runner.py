import sys, io, os

PIP_DIR = None

def _set_pip_dir(d):
    global PIP_DIR
    PIP_DIR = d
    if d and d not in sys.path:
        sys.path.insert(0, d)

def run_code(code, workdir=None):
    """User ka Python code chalao, stdout+stderr capture kar ke return karo."""
    if workdir:
        os.makedirs(workdir, exist_ok=True)
        os.chdir(workdir)
        if workdir not in sys.path:
            sys.path.insert(0, workdir)
    if PIP_DIR and PIP_DIR not in sys.path:
        sys.path.insert(0, PIP_DIR)
    buf = io.StringIO()
    old = (sys.stdout, sys.stderr)
    sys.stdout = sys.stderr = buf
    err = None
    g = {"__name__": "__main__"}
    try:
        exec(code, g)
    except SystemExit:
        pass
    except Exception as e:
        import traceback
        err = traceback.format_exc(limit=3)
    finally:
        sys.stdout, sys.stderr = old
    out = buf.getvalue()
    if err:
        out = (out + "\n" if out else "") + err
    return out.strip() if out.strip() else "✅ Done (no output)"

def pip_install(pkg, pip_dir):
    """Runtime pip install -- pip_dir mein (app ki apni storage)."""
    _set_pip_dir(pip_dir)
    os.makedirs(pip_dir, exist_ok=True)
    buf = io.StringIO()
    old = (sys.stdout, sys.stderr)
    sys.stdout = sys.stderr = buf
    rc = 1
    try:
        try:
            from pip._internal.cli.main import main as pip_main
            rc = pip_main(["install", "--target", pip_dir, "--no-compile", pkg])
        except SystemExit as e:
            rc = e.code if isinstance(e.code, int) else 1
        except Exception:
            import traceback
            buf.write(traceback.format_exc(limit=3))
    finally:
        sys.stdout, sys.stderr = old
    out = buf.getvalue().strip()
    if rc == 0:
        return "✅ " + pkg + " installed" + (("\n" + out[-800:]) if out else "")
    return "pip error (exit " + str(rc) + "):\n" + out[-1200:]
