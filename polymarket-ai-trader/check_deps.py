"""检查依赖是否安装"""
deps = [
    ("requests", "requests"),
    ("dotenv", "python-dotenv"),
    ("openai", "openai"),
    ("jsonschema", "jsonschema"),
    ("py_clob_client", "py-clob-client"),
]

for module_name, pip_name in deps:
    try:
        mod = __import__(module_name)
        ver = getattr(mod, "__version__", "unknown")
        print(f"  ✅ {pip_name} ({ver})")
    except ImportError:
        print(f"  ❌ {pip_name} 未安装 → pip install {pip_name}")
