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
import ProcessKvs.model.RecordingData;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.kinesisvideo.parser.ebml.InputStreamParserByteSource;
import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.regions.Regions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class AudioStreamService {

    private static final Regions REGION = Regions.fromName(System.getenv("REGION"));
    private static final String RECORDINGS_BUCKET_NAME = System.getenv("RECORDINGS_BUCKET_NAME");
    private static final String RECORDINGS_KEY_PREFIX = System.getenv("RECORDINGS_KEY_PREFIX");
    private static final boolean RECORDINGS_PUBLIC_READ_ACL = Boolean.parseBoolean(System.getenv("RECORDINGS_PUBLIC_READ_ACL"));
    private static final String START_SELECTOR_TYPE = System.getenv("START_SELECTOR_TYPE");
    private static final String CLOUDFRONT_DOMAIN = System.getenv("CLOUDFRONT_DOMAIN");
    private static final Logger logger = LoggerFactory.getLogger(AudioStreamService.class);


    public AudioStreamService() {
    }

    public void processAudioStream(RecordingData recording) throws Exception {
        String streamARN = recording.getStreamARN();
        String startFragmentNum = recording.getStartFragmentNum();
        String contactId = recording.getContactId();
        String languageCode = recording.getLanguageCode();

        logger.info(String.format("StreamARN=%s, startFragmentNum=%s, contactId=%s", streamARN, startFragmentNum, contactId));

        long unixTime = System.currentTimeMillis() / 1000L;
        Path saveAudioFilePathFromCustomer = Paths.get("/tmp", contactId + "_" + KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName().toLowerCase()/* + "_" + unixTime*/ + ".raw");
        Path saveAudioFilePathToCustomer = Paths.get("/tmp", contactId + "_" + KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName().toLowerCase()/* + "_" + +unixTime*/ + ".raw");
        logger.info(String.format("Save Path From Customer: %s, Save Path To Customer: %s Start Selector Type: %s", saveAudioFilePathFromCustomer, saveAudioFilePathToCustomer, START_SELECTOR_TYPE));
        FileOutputStream outStreamFromCustomer = new FileOutputStream(saveAudioFilePathFromCustomer.toString());
        FileOutputStream outStreamToCustomer = new FileOutputStream(saveAudioFilePathToCustomer.toString());
        String streamName = streamARN.substring(streamARN.indexOf("/") + 1, streamARN.lastIndexOf("/"));

        InputStream kvsInputStream = KVSUtils.getInputStreamFromKVS(streamName, REGION, startFragmentNum, getAWSCredentials(), START_SELECTOR_TYPE);
        StreamingMkvReader streamingMkvReader = StreamingMkvReader.createDefault(new InputStreamParserByteSource(kvsInputStream));

        FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor = new FragmentMetadataVisitor.BasicMkvTagProcessor();
        FragmentMetadataVisitor fragmentVisitor = FragmentMetadataVisitor.create(Optional.of(tagProcessor));

        boolean bAudioFromCustomer = false;
        boolean bAudioToCustomer = false;

        try {
            logger.info("Saving audio bytes to location");

            Map<String, ByteBuffer> bufferMap = KVSUtils.getByteBufferFromStream(streamingMkvReader, fragmentVisitor, tagProcessor, contactId);
            while (!bufferMap.isEmpty()) {
                if (bufferMap.containsKey(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName())) {
                    // Write audio bytes from the KVS stream to the temporary file
                    ByteBuffer audioBuffer = bufferMap.get(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName());

                    byte[] audioBytes = new byte[audioBuffer.remaining()];
                    audioBuffer.get(audioBytes);
                    outStreamFromCustomer.write(audioBytes);

                    bAudioFromCustomer = true;
                } else if (bufferMap.containsKey(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName())) {
                    // Write audio bytes from the KVS stream to the temporary file
                    ByteBuffer audioBuffer = bufferMap.get(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName());

                    byte[] audioBytes = new byte[audioBuffer.remaining()];
                    audioBuffer.get(audioBytes);
                    outStreamToCustomer.write(audioBytes);

                    bAudioToCustomer = true;
                }
                bufferMap = KVSUtils.getByteBufferFromStream(streamingMkvReader, fragmentVisitor, tagProcessor, contactId);
            }
        } finally {
            logger.info(String.format("Closing file and upload raw audio for contactId: %s ... %s ... %s", contactId, saveAudioFilePathFromCustomer, saveAudioFilePathToCustomer));

            kvsInputStream.close();
            outStreamFromCustomer.close();
            outStreamToCustomer.close();

            Map<String, String> mapAudio = new HashMap<>();
            if (bAudioFromCustomer) {
                mapAudio.put(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName(), saveAudioFilePathFromCustomer.toString());
            }
            if (bAudioToCustomer) {
                mapAudio.put(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName(), saveAudioFilePathToCustomer.toString());
            }

            uploadAudioAndSaveToDdb(recording, mapAudio, unixTime);
        }

    }


    /**
     * Closes the FileOutputStream and uploads the Raw audio file to S3
     *
     * @param recording
     * @param mapAudio
     * @param unixTime
     * @throws IOException
     */
    private void uploadAudioAndSaveToDdb(RecordingData recording, Map<String, String> mapAudio,
                                         long unixTime) throws IOException {
        mapAudio.forEach((k, v) -> {
            File wavFile;
            boolean bAuth = false;
            logger.info(String.format("File: %s, size: %d", k, new File(v).length()));


            if (k.equals(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName()) && (recording.getRecordingAuth() & AudioUtils.AUTH_AUDIO_FROM_CUSTOMER) == AudioUtils.AUTH_AUDIO_FROM_CUSTOMER) {
                bAuth = true;
            } else if (k.equals(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName()) && (recording.getRecordingAuth() & AudioUtils.AUTH_AUDIO_TO_CUSTOMER) == AudioUtils.AUTH_AUDIO_TO_CUSTOMER) {
                bAuth = true;
            }

            if (bAuth) {
                try {
                    wavFile = AudioUtils.convertToWav(v, AudioUtils.CHANNEL_MONO);

                } catch (IOException | UnsupportedAudioFileException e) {
                    throw new RuntimeException(e);
                }

                // Upload the Raw Audio file to S3
                if (wavFile.length() > 0) {
                    S3UploadInfo uploadInfo = AudioUtils.uploadAudio(REGION, RECORDINGS_BUCKET_NAME, RECORDINGS_KEY_PREFIX,
                            wavFile.toString(), recording.getContactId(), RECORDINGS_PUBLIC_READ_ACL, getAWSCredentials());
                    if (k.equals(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName())) {
                        recording.setAudioFromCustomer(uploadInfo.getCloudfrontUrl(CLOUDFRONT_DOMAIN));
                    }
                    if (k.equals(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName())) {
                        recording.setAudioToCustomer(uploadInfo.getCloudfrontUrl(CLOUDFRONT_DOMAIN));
                    }
                } else {
                    logger.info("Skipping upload to S3.  saveCallRecording was disabled or audio file has 0 bytes: " + wavFile.toString());
                }
            }
        });

        if (mapAudio.containsKey(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName())
                && mapAudio.containsKey(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName())
                && recording.getRecordingAuth() == AudioUtils.AUTH_AUDIO_MIXED) {
            File all = AudioUtils.MixAudio(mapAudio.get(KVSUtils.TrackName.AUDIO_FROM_CUSTOMER.getName()), mapAudio.get(KVSUtils.TrackName.AUDIO_TO_CUSTOMER.getName()), recording.getContactId());
            // Upload the Raw Audio file to S3
            if (all.length() > 0) {
                S3UploadInfo uploadInfo = AudioUtils.uploadAudio(REGION, RECORDINGS_BUCKET_NAME, RECORDINGS_KEY_PREFIX,
                        all.toString(), recording.getContactId(), RECORDINGS_PUBLIC_READ_ACL, getAWSCredentials());

                recording.setAudioMixed(uploadInfo.getCloudfrontUrl(CLOUDFRONT_DOMAIN));
            } else {
                logger.info("Skipping upload to S3.  saveCallRecording was disabled or audio file has 0 bytes: " + all);
            }
        }
    }

    /**
     * @return AWS credentials to be used to connect to s3 (for fetching and uploading audio) and KVS
     */
    private static AWSCredentialsProvider getAWSCredentials() {
        return DefaultAWSCredentialsProviderChain.getInstance();
    }

}
