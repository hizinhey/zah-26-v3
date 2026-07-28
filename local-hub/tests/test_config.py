from opshub_hub.config import load_config


def _base_env(**overrides):
    env = {
        "OPSHUB_BACKEND_URL": "https://backend.example.test",
        "OPSHUB_HUB_ID": "hub-1",
        "OPSHUB_HUB_TOKEN": "token",
        "OPSHUB_TEMPLATE_DIR": "/tmp/templates",
        "OPSHUB_WORK_DIR": "/tmp/work",
        "OPSHUB_WDIO_PROJECT_DIR": "/tmp/wdio-project",
        "OPSHUB_NODE_EXECUTABLE": "/usr/bin/node",
    }
    env.update(overrides)
    return env


def test_platform_defaults_to_android_when_unset():
    config = load_config(_base_env())
    assert config.platform == "ANDROID"
    assert config.wdio_project_root is not None
    assert config.node_executable is not None


def test_platform_reads_from_env_when_set():
    config = load_config(_base_env(OPSHUB_PLATFORM="WEB"))
    assert config.platform == "WEB"


def test_android_platform_requires_wdio_project_and_node_executable_env_vars():
    env = _base_env()
    del env["OPSHUB_WDIO_PROJECT_DIR"]
    del env["OPSHUB_NODE_EXECUTABLE"]

    import pytest

    with pytest.raises(ValueError, match="OPSHUB_WDIO_PROJECT_DIR"):
        load_config(env)


def test_web_platform_does_not_require_wdio_project_or_node_executable_env_vars():
    env = _base_env(OPSHUB_PLATFORM="WEB")
    del env["OPSHUB_WDIO_PROJECT_DIR"]
    del env["OPSHUB_NODE_EXECUTABLE"]

    config = load_config(env)

    assert config.platform == "WEB"
    assert config.wdio_project_root is None
    assert config.node_executable is None
