#!/usr/bin/env python3
"""Check the release's identity and compiled protection markers, not clinical safety."""
from io import BytesIO
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION = ET.parse(ROOT / "pom.xml").getroot().findtext("m:version", namespaces=NS)
MODULE = "emrapi"
API_NAME = f"lib/emrapi-api-{VERSION}.jar"
CLASS = "org/openmrs/module/emrapi/adt/AdtServiceImpl.class"
MARKERS = [b"Failed to close inactive visit; rolling back closure batch: ",
           b"Lorg/springframework/transaction/annotation/Transactional;"]

def check(condition, message):
    if not condition:
        raise ValueError(message)


def verify(payload):
    with ZipFile(BytesIO(payload)) as archive:
        names = archive.namelist()
        check(len(names) == len(set(names)), "Duplicate OMOD entries")
        descriptor = ET.fromstring(archive.read("config.xml"))
        check(descriptor.findtext("id") == MODULE, "Wrong module identity")
        check(descriptor.findtext("version") == VERSION, "Wrong descriptor version")
        if API_NAME:
            apis = [n for n in names if n.startswith("lib/" + MODULE + "-api-")
                    and not n.startswith("lib/" + MODULE + "-api-reporting-") and n.endswith(".jar")]
            check(apis == [API_NAME], "Expected exactly one API JAR at the release version")
            with ZipFile(BytesIO(archive.read(API_NAME))) as api:
                implementation = api.read(CLASS)
        else:
            implementation = archive.read(CLASS)
        for marker in MARKERS:
            check(marker in implementation, "Missing compiled protection: " + marker.decode())


def fixture(module=MODULE, version=VERSION, markers=None, api_name=API_NAME, extra_api=False):
    implementation = b" ".join(MARKERS if markers is None else markers)
    output = BytesIO()
    with ZipFile(output, "w") as archive:
        archive.writestr("config.xml", f"<module><id>{module}</id><version>{version}</version></module>")
        if API_NAME:
            inner = BytesIO()
            with ZipFile(inner, "w") as api:
                api.writestr(CLASS, implementation)
            archive.writestr(api_name, inner.getvalue())
            # The real OMOD also ships the optional reporting API; it is not a second main API.
            archive.writestr(f"lib/{MODULE}-api-reporting-{VERSION}.jar", b"synthetic reporting API")
            if extra_api:
                archive.writestr(f"lib/{MODULE}-api-0.0.0.jar", inner.getvalue())
        else:
            archive.writestr(CLASS, implementation)
    return output.getvalue()


def self_test():
    verify(fixture())
    invalid = [fixture(module="other"), fixture(version="0.0.0")]
    invalid.extend(fixture(markers=MARKERS[:i] + MARKERS[i + 1:]) for i in range(len(MARKERS)))
    if API_NAME:
        invalid.append(fixture(api_name=f"lib/{MODULE}-api-0.0.0.jar"))
        invalid.append(fixture(extra_api=True))
    for payload in invalid:
        try:
            verify(payload)
        except (ValueError, KeyError):
            continue
        raise ValueError("Accepted an invalid release fixture")
    print(f"OK: release verifier, 1 positive and {len(invalid)} negative cases")


if __name__ == "__main__":
    check(bool(re.fullmatch(r"\d+\.\d+\.\d+-sihsalus\.\d+", VERSION or "")),
          "Release version must be explicitly SIHSalus-versioned, never SNAPSHOT")
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    else:
        check(len(sys.argv) == 2, "Supply exactly one OMOD")
        artifact = Path(sys.argv[1])
        check(artifact.name == f"{MODULE}-{VERSION}.omod", "Wrong release filename/version")
        verify(artifact.read_bytes())
        print(f"OK: {artifact.name}, module identity, version and compiled protections")
