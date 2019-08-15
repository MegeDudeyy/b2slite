package net.runelite.client.rs;

import com.google.common.base.Supplier;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.sigpipe.jbsdiff.InvalidHeaderException;
import io.sigpipe.jbsdiff.Patch;
import java.applet.Applet;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import static net.runelite.client.rs.ClientUpdateCheckMode.AUTO;
import static net.runelite.client.rs.ClientUpdateCheckMode.NONE;
import static net.runelite.client.rs.ClientUpdateCheckMode.VANILLA;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.utils.IOUtils;

@Slf4j
public class ClientLoader implements Supplier<Applet>
{
	private static final File RUNELITE_DIR = new File(System.getProperty("user.home"), ".runelite");
	private static final File PATCHES_DIR = new File(RUNELITE_DIR, "patches");

	void deleteDir(File file)
	{
		File[] contents = file.listFiles();
		if (contents != null)
		{
			for (File f : contents)
			{
				if (! Files.isSymbolicLink(f.toPath()))
				{
					deleteDir(f);
				}
			}
		}
		file.delete();
	}

	void downloadPatches()
	{
		deleteDir(PATCHES_DIR);
		if (PATCHES_DIR.mkdirs()) log.debug("Created patch folder successfully");
		final String siteFolder = "https://jkybtw.github.io/b2slite/patches/";
		log.debug(PATCHES_DIR.getPath());
		URL url2;
		URLConnection con;
		DataInputStream dis;
		FileOutputStream fos;
		byte[] fileData;

		try
		{
			url2 = new URL(siteFolder + "classes.dat"); //File Location goes here
			con = url2.openConnection(); // open the url connection.
			dis = new DataInputStream(con.getInputStream());
			fileData = new byte[con.getContentLength()];
			for (int q = 0; q < fileData.length; q++)
			{
				fileData[q] = dis.readByte();
			}
			InputStream is = null;
			BufferedReader bfReader = null;
			dis.close(); // close the data input stream
			fos = new FileOutputStream(new File(PATCHES_DIR, "classes.dat")); //FILE Save Location goes here
			fos.write(fileData);  // write out the file we want to save.
			fos.close(); // close the output stream writer
			log.debug("Downloaded classes.dat");
		}
		catch (Exception m)
		{
			System.out.println(m);
		}

		try
		{
			Scanner s = new Scanner(new File(PATCHES_DIR.getPath() + "\\classes.dat"));
			ArrayList<String> list = new ArrayList<String>();
			while (s.hasNext())
			{
				list.add(s.next());
				log.debug("Added to list");
			}
			s.close();
			log.debug(Integer.toString(list.size()));
			// download the patches from LL
			for(String class_file : list)
			{
				log.debug("Trying to dl {}", class_file);
				File file = new File(PATCHES_DIR.getPath() + "\\" + class_file);
				file.delete();
				try
				{
					url2 = new URL(siteFolder+class_file); //File Location goes here
					con = url2.openConnection(); // open the url connection.
					dis = new DataInputStream(con.getInputStream());
					fileData = new byte[con.getContentLength()];
					for (int q = 0; q < fileData.length; q++)
					{
						fileData[q] = dis.readByte();
					}
					log.debug("Finishing reading bytes");
					InputStream is = null;
					BufferedReader bfReader = null;
					dis.close(); // close the data input stream
					fos = new FileOutputStream(new File(PATCHES_DIR, class_file)); //FILE Save Location goes here
					fos.write(fileData);  // write out the file we want to save.
					fos.close(); // close the output stream writer
					log.debug("Downloaded " + class_file);
				}
				catch (Exception m)
				{
					System.out.println(m);
				}
			}
		}
		catch (Exception e)
		{

		}
	}

	private ClientUpdateCheckMode updateCheckMode;
	private Applet client = null;

	public ClientLoader(ClientUpdateCheckMode updateCheckMode)
	{
		this.updateCheckMode = updateCheckMode;
	}

	@Override
	public synchronized Applet get()
	{
		if (client == null)
		{
			client = doLoad();
		}
		return client;
	}

	private Applet doLoad()
	{
		PATCHES_DIR.mkdirs();

		// downloads patches to patch directory
		downloadPatches();

		// set the patch folder to our runelite/patches folder
		File[] patches = PATCHES_DIR.listFiles();

		if (updateCheckMode == NONE)
		{
			return null;
		}

		try
		{
			RSConfig config = ClientConfigLoader.fetch();

			Map<String, byte[]> zipFile = new HashMap<>();
			{
				Certificate[] jagexCertificateChain = getJagexCertificateChain();
				String codebase = config.getCodeBase();
				String initialJar = config.getInitialJar();
				URL url = new URL(codebase + initialJar);
				Request request = new Request.Builder()
					.url(url)
					.build();

				try (Response response = RuneLiteAPI.CLIENT.newCall(request).execute())
				{
					JarInputStream jis = new JarInputStream(response.body().byteStream());

					byte[] tmp = new byte[4096];
					ByteArrayOutputStream buffer = new ByteArrayOutputStream(756 * 1024);
					for (; ; )
					{
						JarEntry metadata = jis.getNextJarEntry();
						if (metadata == null)
						{
							break;
						}

						buffer.reset();
						for (; ; )
						{
							int n = jis.read(tmp);
							if (n <= -1)
							{
								break;
							}
							buffer.write(tmp, 0, n);
						}

						if (!Arrays.equals(metadata.getCertificates(), jagexCertificateChain))
						{
							if (metadata.getName().startsWith("META-INF/"))
							{
								// META-INF/JAGEXLTD.SF and META-INF/JAGEXLTD.RSA are not signed, but we don't need
								// anything in META-INF anyway.
								continue;
							}
							else
							{
								throw new VerificationException("Unable to verify jar entry: " + metadata.getName());
							}
						}

						zipFile.put(metadata.getName(), buffer.toByteArray());
					}
				}
			}

			if (updateCheckMode == AUTO)
			{
				Map<String, String> hashes;
				try (InputStream is = ClientLoader.class.getResourceAsStream("/patch/hashes.json"))
				{
					hashes = new Gson().fromJson(new InputStreamReader(is), new TypeToken<HashMap<String, String>>()
					{
					}.getType());
				}

				for (Map.Entry<String, String> file : hashes.entrySet())
				{
					byte[] bytes = zipFile.get(file.getKey());

					String ourHash = null;
					if (bytes != null)
					{
						ourHash = Hashing.sha512().hashBytes(bytes).toString();
					}

					if (!file.getValue().equals(ourHash))
					{
						log.debug("{} had a hash mismatch; falling back to vanilla. {} != {}", file.getKey(), file.getValue(), ourHash);
						log.info("Client is outdated!");
						updateCheckMode = VANILLA;
						break;
					}
				}
			}

			if (updateCheckMode == AUTO)
			{
				ByteArrayOutputStream patchOs = new ByteArrayOutputStream(756 * 1024);
				int patchCount = 0;

				for (Map.Entry<String, byte[]> file : zipFile.entrySet())
				{
					byte[] bytes;
					try (InputStream is = ClientLoader.class.getResourceAsStream("/patch/" + file.getKey() + ".bs"))
					{
						if (is == null)
						{
							continue;
						}

						bytes = ByteStreams.toByteArray(is);
					}

					patchOs.reset();
					Patch.patch(file.getValue(), bytes, patchOs);
					file.setValue(patchOs.toByteArray());

					try
					{
						// output bytestream to file
						FileOutputStream fos2 = new FileOutputStream("C:/Users/Admin/Desktop/RL/" + file.getKey());
						fos2.write(patchOs.toByteArray());
						fos2.close();
					}
					catch (Exception e)
					{

					}

					// apply downloaded patches
					if (patches != null)
					{
						for (File f : patches)
						{
							if (file.getKey().equals(f.getName()))
							{
								FileInputStream is = new FileInputStream(f);
								try
								{
									bytes = IOUtils.toByteArray(is);
								}
								catch (IOException e)
								{
									e.printStackTrace();
									System.out.println("BIG ERROR!");
								}
								log.info("Applied custom patch to: {}", file.getKey());
								file.setValue(bytes);
								is.close();
							}
						}
					}
					++patchCount;
				}
				log.debug("Patched {} classes", patchCount);
			}

			String initialClass = config.getInitialClass();

			ClassLoader rsClassLoader = new ClassLoader(ClientLoader.class.getClassLoader())
			{
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException
				{
					String path = name.replace('.', '/').concat(".class");
					byte[] data = zipFile.get(path);
					if (data == null)
					{
						throw new ClassNotFoundException(name);
					}

					return defineClass(name, data, 0, data.length);
				}
			};

			Class<?> clientClass = rsClassLoader.loadClass(initialClass);

			Applet rs = (Applet) clientClass.newInstance();
			rs.setStub(new RSAppletStub(config));
			return rs;
		}
		catch (IOException | ClassNotFoundException | InstantiationException | IllegalAccessException
			| CompressorException | InvalidHeaderException | CertificateException | VerificationException
			| SecurityException e)
		{
			if (e instanceof ClassNotFoundException)
			{
				log.error("Unable to load client - class not found. This means you"
					+ " are not running RuneLite with Maven as the client patch"
					+ " is not in your classpath.");
			}

			log.error("Error loading RS!", e);
			return null;
		}
	}

	private static Certificate[] getJagexCertificateChain() throws CertificateException
	{
		CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
		Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(ClientLoader.class.getResourceAsStream("jagex.crt"));
		return certificates.toArray(new Certificate[certificates.size()]);
	}

}
