"""The existing server must protect AI endpoints while Android is developed."""
from unittest.mock import patch

import pytest


@pytest.mark.asyncio
@pytest.mark.parametrize("path", ["/chat/ep_001", "/research/gist_001"])
async def test_ai_routes_require_session(client, path):
    from config import settings

    with patch.object(settings, "test_mode", False):
        response = await client.get(path)
    assert response.status_code == 401
    assert response.json() == {"detail": "Unauthorized"}
