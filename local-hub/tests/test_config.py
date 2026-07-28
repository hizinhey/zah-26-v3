from opshub_hub.config import load_config


def _base_env(**overrides):
    env = {
        "OPSHUB_BACKEND_URL": "https://backend.example.test",
        "OPSHUB_HUB_ID": "hub-1",
        "OPSHUB_HUB_TOKEN": "token",
        "OPSHUB_TEMPLATE_DIR": "/tmp/templates",
        "OPSHUB_WORK_DIR": "/tmp/work",
    }
    env.update(overrides)
    return env


def test_platform_defaults_to_android_when_unset():
    config = load_config(_base_env())
    assert config.platform == "ANDROID"


def test_platform_reads_from_env_when_set():
    config = load_config(_base_env(OPSHUB_PLATFORM="WEB"))
    assert config.platform == "WEB"
