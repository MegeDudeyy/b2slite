package net.runelite.client.plugins.menuentryswapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MoryLegsMode
{
	ECTO("Ecto Teleport"),
	BURGH("Burgh Teleport"),
	OFF("Off");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
