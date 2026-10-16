package com.osrsarmory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Model;
import org.junit.Test;

public class ModelRendererTest
{
	// A red square (two triangles) in front of a blue square further back, plus one hidden face.
	private static Model fakeModel()
	{
		Map<String, Object> data = new HashMap<>();
		data.put("getVerticesCount", 8);
		data.put("getFaceCount", 5);
		data.put("getVerticesX", new float[]{-10, 10, 10, -10, -20, 20, 20, -20});
		data.put("getVerticesY", new float[]{-10, -10, 10, 10, -20, -20, 20, 20});
		data.put("getVerticesZ", new float[]{0, 0, 0, 0, 50, 50, 50, 50});
		data.put("getFaceIndices1", new int[]{0, 0, 4, 4, 0});
		data.put("getFaceIndices2", new int[]{1, 2, 5, 6, 1});
		data.put("getFaceIndices3", new int[]{2, 3, 6, 7, 2});
		int red = (0 << 10) | (7 << 7) | 64;   // hue 0, full saturation, mid lightness
		int blue = (42 << 10) | (7 << 7) | 64; // hue ~2/3
		data.put("getFaceColors1", new int[]{red, red, blue, blue, red});
		data.put("getFaceColors2", new int[]{red, red, blue, blue, red});
		data.put("getFaceColors3", new int[]{-1, -1, -1, -1, -2});
		data.put("getFaceTransparencies", null);
		data.put("getFaceTextures", null);

		return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
			(proxy, method, args) -> data.get(method.getName()));
	}

	@Test
	public void rendersNearestFaceOnTopWithTransparentBackground()
	{
		BufferedImage image = ModelRenderer.render(new ModelRenderer.Snapshot(fakeModel()), 100, 100);
		assertNotNull(image);

		int center = image.getRGB(50, 50);
		int edge = image.getRGB(15, 50);   // only the back (blue) square covers this
		int corner = image.getRGB(0, 0);   // outside both squares

		assertEquals("centre is opaque", 0xFF, center >>> 24);
		assertTrue("front square (red) wins the depth test", ((center >> 16) & 0xFF) > (center & 0xFF));
		assertTrue("back square (blue) visible around it", (edge & 0xFF) > ((edge >> 16) & 0xFF));
		assertEquals("background is transparent", 0, corner >>> 24);
	}
}
