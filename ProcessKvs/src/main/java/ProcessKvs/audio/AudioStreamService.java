/******************************************************************************
 *  Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved. 
 *  Licensed under the Apache License Version 2.0 (the 'License'). You may not
 *  use this file except in compliance with the License. A copy of the License
 *  is located at                                                            
 *                                                                              
 *      http://www.apache.org/licenses/                                        
 *  or in the 'license' file accompanying this file. This file is distributed on
 *  an 'AS IS' BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express or
 *  implied. See the License for the specific language governing permissions and
 *  limitations under the License.                                              
******************************************************************************/

package ProcessKvs.audio;

import ProcessKvs.kvstream.KVSUtils;
import ProcessKvs.kvstream.S3UploadInfo;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.kinesisvideo.parser.ebml.InputStreamParserByteSource;
import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.regions.Regions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;


public class AudioStreamService {

    private static final Regions REGION = Regions.fromName(System.getenv("REGION"));
    private static final String RECORDINGS_BUCKET_NAME = System.getenv("RECORDINGS_BUCKET_NAME");
    private static final String RECORDINGS_KEY_PREFIX = System.getenv("RECORDINGS_KEY_PREFIX");
    private static final boolean RECORDINGS_PUBLIC_READ_ACL = Boolean.parseBoolean(System.getenv("RECORDINGS_PUBLIC_READ_ACL"));
    private static final String START_SELECTOR_TYPE = System.getenv("START_SELECTOR_TYPE");
    private static final Logger logger = LoggerFactory.getLogger(AudioStreamService.class);


    public AudioStreamService() {
    }

    public void processAudioStream(
            String streamARN, String startFragmentNum, String contactId,
            Optional<String> languageCode) throws Exception {

        logger.info(String.format("StreamARN=%s, startFragmentNum=%s, contactId=%s", streamARN, startFragmentNum, contactId));

        long unixTime = System.currentTimeMillis() / 1000L;
        Path saveAudioFilePathFromCustomer = Paths.get("/tmp", contactId + "_" + KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName() + "_" + unixTime + ".raw");
        Path saveAudioFilePathToCustomer = Paths.get("/tmp", contactId + "_" + KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName() + "_" + +unixTime + ".raw");
        System.out.println(String.format("Save Path From Customer: %s, Save Path To Customer: %s Start Selector Type: %s", saveAudioFilePathFromCustomer, saveAudioFilePathToCustomer, START_SELECTOR_TYPE));
        FileOutputStream fileOutputStreamFromCustomer = new FileOutputStream(saveAudioFilePathFromCustomer.toString());
        FileOutputStream fileOutputStreamToCustomer = new FileOutputStream(saveAudioFilePathToCustomer.toString());
        String streamName = streamARN.substring(streamARN.indexOf("/") + 1, streamARN.lastIndexOf("/"));

        InputStream kvsInputStream = KVSUtils.getInputStreamFromKVS(streamName, REGION, startFragmentNum, getAWSCredentials(), START_SELECTOR_TYPE);
        StreamingMkvReader streamingMkvReader = StreamingMkvReader.createDefault(new InputStreamParserByteSource(kvsInputStream));

        FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor = new FragmentMetadataVisitor.BasicMkvTagProcessor();
        FragmentMetadataVisitor fragmentVisitor = FragmentMetadataVisitor.create(Optional.of(tagProcessor));

        try {
            logger.info("Saving audio bytes to location");

            Map<String, ByteBuffer> bufferMap = KVSUtils.getByteBufferFromStream(streamingMkvReader, fragmentVisitor, tagProcessor, contactId);
            while (!bufferMap.isEmpty()) {
                if (bufferMap.containsKey(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName())) {
                    // Write audio bytes from the KVS stream to the temporary file
                    ByteBuffer audioBuffer = bufferMap.get(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName());

                    byte[] audioBytes = new byte[audioBuffer.remaining()];
                    audioBuffer.get(audioBytes);
                    fileOutputStreamFromCustomer.write(audioBytes);
                } else if (bufferMap.containsKey(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName())) {
                    // Write audio bytes from the KVS stream to the temporary file
                    ByteBuffer audioBuffer = bufferMap.get(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName());

                    byte[] audioBytes = new byte[audioBuffer.remaining()];
                    audioBuffer.get(audioBytes);
                    fileOutputStreamToCustomer.write(audioBytes);
                }
                bufferMap = KVSUtils.getByteBufferFromStream(streamingMkvReader, fragmentVisitor, tagProcessor, contactId);
            }
        } finally {
            logger.info(String.format("Closing file and upload raw audio for contactId: %s ... %s ... %s", contactId, saveAudioFilePathFromCustomer, saveAudioFilePathToCustomer));
            closeFileAndUploadRawAudio(
                    kvsInputStream, fileOutputStreamFromCustomer, saveAudioFilePathFromCustomer, contactId,
                    unixTime, languageCode.get()
            );
            closeFileAndUploadRawAudio(
                    kvsInputStream, fileOutputStreamToCustomer, saveAudioFilePathToCustomer, contactId,
                    unixTime, languageCode.get()
            );
        }

        MixAudio(saveAudioFilePathFromCustomer, saveAudioFilePathToCustomer, contactId);
    }

    public void MixAudio(Path fromCustomer, Path toCustomer, String contactId) {
        long unixTime = System.currentTimeMillis() / 1000L;
        File output = new File(String.format("/tmp/%s_ALL_%s.wav", contactId, unixTime));
        try {

            File fromCustomerFile = new File(fromCustomer.toString().replace(".raw", ".wav"));
            File toCustomerFile = new File(toCustomer.toString().replace(".raw", ".wav"));

            logger.info(String.format("file size: %s --- %s", fromCustomerFile.length(), toCustomerFile.length()));

            AudioInputStream from = AudioSystem.getAudioInputStream(fromCustomerFile);
            AudioInputStream to = AudioSystem.getAudioInputStream(toCustomerFile);

            output = mixSamples(from, to, output, contactId);

            // Close streams
            from.close();
            to.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    // Mix two 16-bit signed samples into one
    private File mixSamples(AudioInputStream from, AudioInputStream to, File output, String contactId) throws IOException {
        AudioFormat format = from.getFormat();
        int frameSize = format.getFrameSize();
        int sampleSizeInBits = format.getSampleSizeInBits();
        int size = from.available();

        byte[] data1 = new byte[size];
        byte[] data2 = new byte[size];

        int data1Len = from.read(data1);
        int data2Len = to.read(data2);

        byte[] result = new byte[size];

        // Mix frame sample-by-sample
        for (int i = 0; i < data1.length; i += frameSize) {
            int mixedSample;
            // Assume 16-bit samples
            short sample1, sample2;
            // Convert bytes to 16-bit sample
            sample1 = getSample(data1[i], data1[i + 1]);

            sample2 = getSample(data2[i], data2[i + 1]);

            // Mix samples
            mixedSample = sample1 + sample2;

            // Clip if outside 16-bit range
            // enforce min and max (may introduce clipping)
            mixedSample = Math.min(Short.MAX_VALUE, mixedSample);
            mixedSample = Math.max(Short.MIN_VALUE, mixedSample);

            // Convert back to bytes
            result[i] = (byte) (mixedSample >> 8);
            result[i + 1] = (byte) (mixedSample & 0xFF);
        }

        AudioInputStream mixedStream = new AudioInputStream(new ByteArrayInputStream(result), format, result.length);


        logger.info(String.format("mixedStream size: %s", mixedStream.available()));

        // Write mixed audio data to output file
        AudioSystem.write(mixedStream, AudioFileFormat.Type.WAVE, output);

        logger.info(String.format("output file size: %s", output.length()));

        // Upload the Raw Audio file to S3
        if (output.length() > 0) {
            S3UploadInfo uploadInfo = AudioUtils.uploadRawAudio(REGION, RECORDINGS_BUCKET_NAME, RECORDINGS_KEY_PREFIX,
                    output.toString(), contactId, RECORDINGS_PUBLIC_READ_ACL, getAWSCredentials(), AudioUtils.CHANNEL_STEREO);
        } else {
            logger.info("Skipping upload to S3.  saveCallRecording was disabled or audio file has 0 bytes: " + output);
        }

        return output;
    }

    // Convert two bytes to a 16-bit signed sample
    short getSample(byte lower, byte upper) {
        return (short) ((lower << 8) | (upper & 0xFF));
    }

    /**
     * Closes the FileOutputStream and uploads the Raw audio file to S3
     *
     * @param kvsInputStream
     * @param fileOutputStream
     * @param saveAudioFilePath
     * @throws IOException
     */
    private void closeFileAndUploadRawAudio(InputStream kvsInputStream, FileOutputStream fileOutputStream,
                                            Path saveAudioFilePath, String contactId,
                                            long unixTime, String languageCode) throws IOException {

        kvsInputStream.close();
        fileOutputStream.close();

        logger.info(String.format("File size: %d", new File(saveAudioFilePath.toString()).length()));

        // Upload the Raw Audio file to S3
        if (new File(saveAudioFilePath.toString()).length() > 0) {
            S3UploadInfo uploadInfo = AudioUtils.uploadRawAudio(REGION, RECORDINGS_BUCKET_NAME, RECORDINGS_KEY_PREFIX,
                    saveAudioFilePath.toString(), contactId, RECORDINGS_PUBLIC_READ_ACL, getAWSCredentials(), 1);
        } else {
            logger.info("Skipping upload to S3.  saveCallRecording was disabled or audio file has 0 bytes: " + saveAudioFilePath);
        }
    }

    /**
     * @return AWS credentials to be used to connect to s3 (for fetching and uploading audio) and KVS
     */
    private static AWSCredentialsProvider getAWSCredentials() {
        return DefaultAWSCredentialsProviderChain.getInstance();
    }


}
