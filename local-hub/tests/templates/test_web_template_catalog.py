from pathlib import Path

from opshub_hub.models import TemplateParametersV1
from opshub_hub.templates import TemplateCatalog

TEMPLATE_ROOT = Path(__file__).resolve().parents[2] / "templates" / "web"

SAMPLE_PARAMETERS = TemplateParametersV1(
    oaName="zBusiness",
    thumbnailUrl="https://res-zalo.zadn.vn/upload/media/2025/9/16/thumb.png",
    expectedHeader="Header",
    expectedBody="Body",
    expectedButtonText="Nâng cấp ngay",
    expectedRedirectUrl="https://business.zbox.vn/nang-cap-business-lite?value_type=2",
    expectedRedirectDomain="business.zbox.vn",
)


def test_manifest_declares_five_web_templates():
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    assert catalog.catalog_version == "web-v1"
    for template_id in (
        "web-oa-delivery-v1",
        "web-thumbnail-v1",
        "web-content-v1",
        "web-button-text-v1",
        "web-redirect-v1",
    ):
        assert catalog.entry(template_id).id == template_id


def test_catalog_verifies_checksums():
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    catalog.verify()


def test_every_template_renders_with_no_leftover_placeholders():
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    values = SAMPLE_PARAMETERS.model_dump()
    for template_id in (
        "web-oa-delivery-v1",
        "web-thumbnail-v1",
        "web-content-v1",
        "web-button-text-v1",
        "web-redirect-v1",
    ):
        rendered = catalog.render(template_id, values)
        assert "{{" not in rendered
        assert "zBusiness" in rendered
