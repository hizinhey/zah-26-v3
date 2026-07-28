import pytest

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


def test_platforms_defaults_to_android_only_when_unset():
    config = load_config(_base_env())
    assert config.platforms == ("ANDROID",)


def test_platforms_reads_a_comma_separated_list_from_env():
    config = load_config(_base_env(OPSHUB_PLATFORMS="ANDROID,WEB"))
    assert config.platforms == ("ANDROID", "WEB")


def test_platforms_tolerates_stray_whitespace_around_commas():
    config = load_config(_base_env(OPSHUB_PLATFORMS=" ANDROID , WEB "))
    assert config.platforms == ("ANDROID", "WEB")


def test_platform_template_root_derives_a_per_platform_subdirectory():
    config = load_config(_base_env(OPSHUB_TEMPLATE_DIR="/tmp/templates"))
    assert str(config.platform_template_root("ANDROID")) == "/tmp/templates/android"
    assert str(config.platform_template_root("WEB")) == "/tmp/templates/web"


def test_both_platforms_require_wdio_project_and_node_executable_env_vars():
    for platform_overrides in ({}, {"OPSHUB_PLATFORMS": "WEB"}):
        env = _base_env(**platform_overrides)
        del env["OPSHUB_WDIO_PROJECT_DIR"]
        del env["OPSHUB_NODE_EXECUTABLE"]

        with pytest.raises(ValueError, match="OPSHUB_WDIO_PROJECT_DIR"):
            load_config(env)
