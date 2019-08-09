package com.hedera.downloader;

import com.hedera.configLoader.ConfigLoader;
import com.hedera.parser.RecordFileParser;
import com.hedera.signatureVerifier.NodeSignatureVerifier;
import com.hedera.utilities.Utility;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RecordFileDownloader extends Downloader {

	private static String validRcdDir = null;

	public RecordFileDownloader(ConfigLoader configLoader) {
		super(configLoader);
	}

	public static void downloadNewRecordfiles(RecordFileDownloader downloader) {
		setupCloudConnection();

		HashMap<String, List<File>> sigFilesMap;
		try {
			sigFilesMap = downloader.downloadSigFiles(DownloadType.RCD);

			// Verify signature files and download .rcd files of valid signature files
			downloader.verifySigsAndDownloadRecordFiles(sigFilesMap);

			if (validRcdDir != null) {
//				new Thread(() -> {
					verifyValidRecordFiles(validRcdDir);
//				}).start();
			}

			xfer_mgr.shutdownNow();

		} catch (IOException e) {
			log.error(MARKER, "IOException: {}", e);
		}
	}

	public static void main(String[] args) {
		if (Utility.checkStopFile()) {
			log.info(MARKER, "Stop file found, exiting.");
			System.exit(0);
		}
		configLoader = new ConfigLoader();

		RecordFileDownloader downloader = new RecordFileDownloader(configLoader);

		while (true) {
			if (Utility.checkStopFile()) {
				log.info(MARKER, "Stop file found, stopping.");
				break;
			}
			downloadNewRecordfiles(downloader);
		}
	}

	/**
	 * Check if there is any missing .rcd file:
	 * (1) Sort .rcd files by timestamp,
	 * (2) Verify the .rcd files to see if the file Hash matches prevFileHash
	 * @param validDir
	 */
	public static void verifyValidRecordFiles(String validDir) {
		String lastValidRcdFileName = configLoader.getLastValidRcdFileName();
		String lastValidRcdFileHash = configLoader.getLastValidRcdFileHash();

		File validDirFile = new File(validDir);
		if (!validDirFile.exists()) {
			return;
		}
		try (Stream<Path> pathStream = Files.walk(validDirFile.toPath())) {
			List<String> fileNames = pathStream.filter(p -> Utility.isRecordFile(p.toString()))
					.filter(p -> lastValidRcdFileName.isEmpty() ||
							fileNameComparator.compare(p.toFile().getName(), lastValidRcdFileName) > 0)
					.sorted(pathComparator)
					.map(p -> p.toString()).collect(Collectors.toList());

			String newLastValidRcdFileName = lastValidRcdFileName;
			String newLastValidRcdFileHash = lastValidRcdFileHash;

			for (String rcdName : fileNames) {
				if (Utility.checkStopFile()) {
					log.info(MARKER, "Stop file found, stopping");
					break;
				}
				String prevFileHash = RecordFileParser.readPrevFileHash(rcdName);
				if (prevFileHash == null) {
					log.info(MARKER, "{} doesn't contain valid prevFileHash", rcdName);
					break;
				}
				if (newLastValidRcdFileHash.isEmpty() ||
						newLastValidRcdFileHash.equals(prevFileHash) ||
						prevFileHash.equals(Hex.encodeHexString(new byte[48]))) {
					newLastValidRcdFileHash = Utility.bytesToHex(Utility.getFileHash(rcdName));
					newLastValidRcdFileName = new File(rcdName).getName();
				} else {
					break;
				}
			}

			if (!newLastValidRcdFileName.equals(lastValidRcdFileName)) {
				configLoader.setLastValidRcdFileHash(newLastValidRcdFileHash);
				configLoader.setLastValidRcdFileName(newLastValidRcdFileName);
				configLoader.saveRecordsDataToFile();
			}

		} catch (IOException ex) {
			log.error(MARKER, "verifyValidRcdFiles :: An Exception occurs while traversing {} : {}", validDir, ex);
		}
	}

	/**
	 *  For each group of signature Files with the same file name:
	 *  (1) verify that the signature files are signed by corresponding node's PublicKey;
	 *  (2) For valid signature files, we compare their Hashes to see if more than 2/3 Hashes matches.
	 *  If more than 2/3 Hashes matches, we download the corresponding .rcd file from a node folder which has valid signature file.
	 *  (3) compare the Hash of .rcd file with Hash which has been agreed on by valid signatures, if match, move the .rcd file into `valid` directory; else download .rcd file from other valid node folder, and compare the Hash until find a match one
	 *  return the name of directory which contains valid .rcd files
	 * @param sigFilesMap
	 */
	String verifySigsAndDownloadRecordFiles(Map<String, List<File>> sigFilesMap) {

		// reload address book and keys
		NodeSignatureVerifier verifier = new NodeSignatureVerifier(configLoader);

		validRcdDir = null;

		for (String fileName : sigFilesMap.keySet()) {
			if (Utility.checkStopFile()) {
				log.info(MARKER, "Stop file found, stopping");
				break;
			}
			List<File> sigFiles = sigFilesMap.get(fileName);
			// If the number of sigFiles is not greater than 2/3 of number of nodes, we don't need to verify them
			if (!Utility.greaterThanSuperMajorityNum(sigFiles.size(), nodeAccountIds.size())) {
				continue;
			} else {
				// validSigFiles are signed by node'key and contains the same Hash which has been agreed by more than 2/3 nodes
				List<File> validSigFiles = verifier.verifySignatureFiles(sigFiles);
				if (validSigFiles != null) {
					for (File validSigFile : validSigFiles) {
						if (Utility.checkStopFile()) {
							log.info(MARKER, "Stop file found, stopping");
							break;
						}
						if (validRcdDir == null) {
							validRcdDir = validSigFile.getParentFile().getParent() + "/valid/";
						}
						Pair<Boolean, File> rcdFileResult = downloadRcdFile(validSigFile, validRcdDir);
						File rcdFile = rcdFileResult.getRight();
						if (rcdFile != null &&
								Utility.hashMatch(validSigFile, rcdFile)) {
							break;
						} else if (rcdFile != null) {
							log.warn(MARKER, "{}'s Hash doesn't match the Hash contained in valid signature file. Will try to download a rcd file with same timestamp from other nodes and check the Hash.", rcdFile.getPath());
						}
					}
				} else {
					log.info(MARKER, "No valid signature files");
				}
			}
		}
		return validRcdDir;
	}

	Pair<Boolean, File> downloadRcdFile(File sigFile, String validRcdDir) {
		String nodeAccountId = Utility.getAccountIDStringFromFilePath(sigFile.getPath());
		String sigFileName = sigFile.getName();
		String rcdFileName = sigFileName.replace(".rcd_sig", ".rcd");
		String s3ObjectKey = "recordstreams/record" + nodeAccountId + "/" + rcdFileName;
//		String localFileName = validRcdDir + rcdFileName;
		return saveToLocal(bucketName, s3ObjectKey, validRcdDir + rcdFileName);
	}

}
