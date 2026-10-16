package com.osrsarmory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;

/**
 * Pictures the local player automatically: after login and after every gear change, once the
 * player is standing still, their model is rendered (see ModelRenderer) and handed over as a PNG.
 */
@Slf4j
@Singleton
class AvatarCapture
{
	private static final int WIDTH = 180;
	private static final int HEIGHT = 280;
	// Give the model a couple of ticks to update after a gear change.
	private static final int SETTLE_TICKS = 2;

	@Inject
	private Client client;

	@Inject
	private ScheduledExecutorService executor;

	private boolean dirty = true;
	private int settle = SETTLE_TICKS;
	private byte[] lastPng;

	void reset()
	{
		dirty = true;
		settle = SETTLE_TICKS;
		lastPng = null;
	}

	// Called when the worn equipment changes: the picture needs redrawing.
	void markDirty()
	{
		dirty = true;
		settle = SETTLE_TICKS;
	}

	/**
	 * Called every game tick on the client thread. When a new picture is ready, the PNG (base64)
	 * is passed to onCaptured on a background thread. Unchanged pictures aren't passed on.
	 */
	void onTick(Consumer<String> onCaptured)
	{
		if (!dirty)
		{
			return;
		}
		if (settle > 0)
		{
			settle--;
			return;
		}

		Player player = client.getLocalPlayer();
		// Wait for the idle stance, so the picture isn't caught mid-swing or mid-step.
		if (player == null || player.getAnimation() != -1 || player.getPoseAnimation() != player.getIdlePoseAnimation())
		{
			return;
		}
		Model model = player.getModel();
		if (model == null)
		{
			return;
		}
		Model unskewed = model.getUnskewedModel();
		ModelRenderer.Snapshot snapshot = new ModelRenderer.Snapshot(unskewed != null ? unskewed : model);
		dirty = false;

		executor.execute(() ->
		{
			BufferedImage image = ModelRenderer.render(snapshot, WIDTH, HEIGHT);
			if (image == null)
			{
				return;
			}
			try (ByteArrayOutputStream out = new ByteArrayOutputStream())
			{
				ImageIO.write(image, "png", out);
				byte[] png = out.toByteArray();
				if (Arrays.equals(png, lastPng))
				{
					return;
				}
				lastPng = png;
				onCaptured.accept(Base64.getEncoder().encodeToString(png));
			}
			catch (IOException e)
			{
				log.debug("OSRS Armory: failed to encode avatar", e);
			}
		});
	}
}
