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

import ProcessKvs.kvstream.S3UploadInfo;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Utility class to download/upload audio files from/to S3
 *
 * <p>Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.</p>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
public final class AudioUtils {

    public static final int CHANNEL_MONO = 1;
    public static final int CHANNEL_STEREO = 2;

    public static final int AUTH_AUDIO_NONE = 0;
    public static final int AUTH_AUDIO_FROM_CUSTOMER = 1;
    public static final int AUTH_AUDIO_TO_CUSTOMER = 2;
    public static final int AUTH_AUDIO_MIXED = 3;

    private static final Logger logger = LoggerFactory.getLogger(AudioUtils.class);

    /**
     * Converts the given raw audio data into a wav file. Returns the wav file back.
     */
    public static File convertToWav(String audioFilePath, int channels) throws IOException, UnsupportedAudioFileException {
        File outputFile = new File(audioFilePath.replace(".raw", ".wav"));
        AudioInputStream source = new AudioInputStream(Files.newInputStream(Paths.get(audioFilePath)),
                new AudioFormat(8000, 16, channels, true, false), -1); // 8KHz, 16 bit, 1 channel, signed, little-endian
        AudioSystem.write(source, AudioFileFormat.Type.WAVE, outputFile);
        return outputFile;
    }

    /**
     * Saves the raw audio file as an S3 object
     *
     * @param region
     * @param bucketName
     * @param keyPrefix
     * @param audioFilePath
     * @param awsCredentials
     */
    public static S3UploadInfo uploadAudio(Regions region, String bucketName, String keyPrefix, String audioFilePath,
                                           String contactId, boolean publicReadAcl,
                                           AWSCredentialsProvider awsCredentials) {
        File wavFile = new File(audioFilePath);
        S3UploadInfo uploadInfo = null;

        try {

            AmazonS3Client s3Client = (AmazonS3Client)AmazonS3ClientBuilder.standard()
                    .withRegion(region)
                    .withCredentials(awsCredentials)
                    .build();

            // upload the raw audio file to the designated S3 location
            String objectKey = keyPrefix + wavFile.getName();

            logger.info(String.format("Uploading Audio: to %s/%s from %s", bucketName, objectKey, wavFile));
            PutObjectRequest request = new PutObjectRequest(bucketName, objectKey, wavFile);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("audio/wav");
            metadata.addUserMetadata("contact-id", contactId);
            request.setMetadata(metadata);

            if (publicReadAcl) {
                request.setCannedAcl(CannedAccessControlList.PublicRead);
            }

            PutObjectResult s3result = s3Client.putObject(request);

            logger.info("putObject completed successfully " + s3result.getETag());
            uploadInfo = new S3UploadInfo(bucketName, objectKey, region);

        } catch (SdkClientException e) {
            logger.error("Audio upload to S3 failed: ", e);
            throw e;
        } finally {
            if (wavFile != null) {
                //wavFile.delete();
            }
        }

        return uploadInfo;
    }



    public static File MixAudio(String fromCustomer, String toCustomer, String contactId) {
        long unixTime = System.currentTimeMillis() / 1000L;
        File output = new File(String.format("/tmp/%s_mixed_%s.wav", contactId, unixTime));
        try {

            File fromCustomerFile = new File(fromCustomer.replace(".raw", ".wav"));
            File toCustomerFile = new File(toCustomer.replace(".raw", ".wav"));

            logger.info(String.format("file size: %s --- %s", fromCustomerFile.length(), toCustomerFile.length()));

            AudioInputStream from = AudioSystem.getAudioInputStream(fromCustomerFile);
            AudioInputStream to = AudioSystem.getAudioInputStream(toCustomerFile);

            mixSamples(from, to, output, contactId);

            // Close streams
            from.close();
            to.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

        return output;
    }


    // Mix two 16-bit signed samples into one
    public static void mixSamples(AudioInputStream from, AudioInputStream to, File output, String contactId) throws IOException {
        AudioFormat format = from.getFormat();
        int frameSize = format.getFrameSize();
        int sampleSizeInBits = format.getSampleSizeInBits();
        int size = from.available();

        byte[] result = new byte[size];

        // Mix frame sample-by-sample
        for (int i = 0; i < size; i += frameSize) {
            int mixedSample;
            // Assume 16-bit samples
            short sample1, sample2;

            byte[] data1 = new byte[frameSize];
            byte[] data2 = new byte[frameSize];

            int data1Len = from.read(data1);
            int data2Len = to.read(data2);

            if(data1Len <= 0 || data2Len <= 0)
            {
                break;
            }

            // Convert bytes to 16-bit sample
            sample1 = getSample(data1[0], data1[1]);

            sample2 = getSample(data2[0], data2[1]);

            // Mix samples
            mixedSample = sample1 + sample2;

            // Clip if outside 16-bit range
            // enforce min and max (may introduce clipping)
            mixedSample = Math.min(Short.MAX_VALUE, mixedSample);
            mixedSample = Math.max(Short.MIN_VALUE, mixedSample);

            // Convert back to bytes in bigEndian model
            result[i] = (byte) (mixedSample >> 8);
            result[i + 1] = (byte) (mixedSample & 0xFF);
        }

        AudioFormat audioFormat = new AudioFormat(8000, 16, CHANNEL_MONO, true, false);
        AudioInputStream mixedStream = new AudioInputStream(new ByteArrayInputStream(result), audioFormat, result.length);

        logger.info(String.format("mixedStream size: %s", mixedStream.available()));

        // Write mixed audio data to output file
        AudioSystem.write(mixedStream, AudioFileFormat.Type.WAVE, output);

        logger.info(String.format("output file size: %s", output.length()));
    }

    // Convert two bytes to a 16-bit signed sample in bigEndian model
    public static short getSample(byte lower, byte upper) {
        return (short) ((lower << 8) | (upper & 0xFF));
    }
}
