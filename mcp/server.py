"""WristEyes MCP server: lets an AI assistant talk to your Wear OS watch.

The watch runs the WristEyes app, which serves a small JSON API on your
Wi-Fi. This server turns that API into MCP tools.

Config (environment variables):
  WRISTEYES_URL    e.g. http://192.168.1.159:8765
  WRISTEYES_TOKEN  the token shown in the WristEyes app on the watch
"""
import os
from typing import Any, Literal

import httpx
from mcp.server.mcpserver import MCPServer

URL = os.environ.get("WRISTEYES_URL", "").rstrip("/")
TOKEN = os.environ.get("WRISTEYES_TOKEN", "")

mcp = MCPServer(
    "wristeyes",
    instructions=(
        "Tools for the user's own Wear OS smartwatch. Use them to read the watch's "
        "sensors (heart rate, steps, pressure, compass...), check battery, and to "
        "reach the user on the wrist: vibrate, notify, show text, or speak aloud."
    ),
)

Sensor = Literal[
    "heart_rate", "steps", "pressure", "light", "accelerometer",
    "gyroscope", "magnetic_field", "compass", "offbody",
]


async def call(method: str, path: str, *, params: dict | None = None,
               body: dict | None = None, timeout: float = 15) -> dict[str, Any]:
    if not URL or not TOKEN:
        return {"ok": False, "error": "set WRISTEYES_URL and WRISTEYES_TOKEN"}
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            r = await client.request(
                method, URL + path, params=params, json=body,
                headers={"Authorization": f"Bearer {TOKEN}"},
            )
        return r.json()
    except httpx.TimeoutException:
        return {"ok": False, "error": "watch did not answer in time (screen off or Wi-Fi asleep?)"}
    except httpx.HTTPError as e:
        return {"ok": False, "error": f"cannot reach the watch at {URL}: {e}"}


@mcp.tool()
async def watch_info() -> dict[str, Any]:
    """Model, Android version, battery percent, charging state, screen state and IP of the watch."""
    return await call("GET", "/info")


@mcp.tool()
async def list_sensors() -> dict[str, Any]:
    """Which sensors this watch has (short names usable with read_sensor, plus full hardware list)."""
    return await call("GET", "/sensors")


@mcp.tool()
async def read_sensor(sensor: Sensor) -> dict[str, Any]:
    """Read one value from a watch sensor.

    heart_rate returns bpm (takes 5-30 s, watch must be on the wrist);
    steps returns steps since the watch booted; pressure returns hPa and
    altitude; compass returns heading in degrees; offbody says if the watch
    is being worn; light returns lux; the others return x/y/z.
    """
    wait = 45 if sensor == "heart_rate" else 15
    return await call("GET", "/sensor", params={"type": sensor}, timeout=wait)


@mcp.tool()
async def vibrate(ms: int = 400, times: int = 1) -> dict[str, Any]:
    """Vibrate the watch. ms: length of each buzz (20-3000), times: how many buzzes (1-10)."""
    return await call("POST", "/vibrate", body={"ms": ms, "times": times})


@mcp.tool()
async def notify(text: str, title: str = "Claude") -> dict[str, Any]:
    """Post a notification on the watch (stays in the notification list)."""
    return await call("POST", "/notify", body={"title": title, "text": text})


@mcp.tool()
async def show_text(text: str, seconds: int = 8) -> dict[str, Any]:
    """Show big text on the whole watch screen for a few seconds (max 300 characters)."""
    return await call("POST", "/show", body={"text": text, "seconds": seconds})


@mcp.tool()
async def say(text: str, lang: str = "") -> dict[str, Any]:
    """Speak text aloud through the watch speaker. lang: BCP-47 tag like 'ru', 'en', 'ro' (default: watch language)."""
    return await call("POST", "/say", body={"text": text, "lang": lang})


if __name__ == "__main__":
    mcp.run()
