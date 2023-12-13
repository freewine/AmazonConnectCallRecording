package ProcessKvs.kvstream;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.kinesisvideo.parser.ebml.MkvTypeInfos;
import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.MkvDataElement;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElement;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitException;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;
import com.amazonaws.kinesisvideo.parser.mkv.MkvStartMasterElement;
import com.amazonaws.kinesisvideo.parser.mkv.MkvValue;
import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTag;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMediaClientBuilder;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaResult;
import com.amazonaws.services.kinesisvideo.model.StartSelector;
import com.amazonaws.services.kinesisvideo.model.StartSelectorType;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Optional;

import static com.amazonaws.util.StringUtils.isNullOrEmpty;

/**
 * Utility class to interact with KVS streams
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
public final class KVSUtils {
    public enum TrackName {
        AUDIO_FROM_CUSTOMER("AUDIO_FROM_CUSTOMER"),
        AUDIO_TO_CUSTOMER("AUDIO_TO_CUSTOMER");

        private String name;

        TrackName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(KVSUtils.class);

    /**
     * Iterates thorugh all the tags and retrieves the Tag value for "ContactId" tag
     *
     * @param tagProcessor
     * @return
     */
    private static String getContactIdFromStreamTag(FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor) {
        Iterator iter = tagProcessor.getTags().iterator();
        while (iter.hasNext()) {
            MkvTag tag = (MkvTag) iter.next();
            if ("ContactId".equals(tag.getTagName())) {
                return tag.getTagValue();
            }
        }
        return null;
    }

    /**
     * Fetches the next ByteBuffer of size 1024 bytes from the KVS stream by parsing the frame from the MkvElement
     * Each frame has a ByteBuffer having size 1024
     *
     * @param streamingMkvReader
     * @param fragmentVisitor
     * @param tagProcessor
     * @param contactId
     * @return
     * @throws MkvElementVisitException
     */
    @SuppressWarnings("unchecked")
    public static ByteBuffer getByteBufferFromStream(StreamingMkvReader streamingMkvReader,
                                                     FragmentMetadataVisitor fragmentVisitor,
                                                     FragmentMetadataVisitor.BasicMkvTagProcessor tagProcessor,
                                                     String contactId) throws MkvElementVisitException {

        while (streamingMkvReader.mightHaveNext()) {
            Optional<MkvElement> mkvElementOptional = streamingMkvReader.nextIfAvailable();
            if (mkvElementOptional.isPresent()) {

                MkvElement mkvElement = mkvElementOptional.get();
                mkvElement.accept(fragmentVisitor);

                // Validate that we are reading data only for the expected contactId at start of every mkv master element
                if (MkvTypeInfos.EBML.equals(mkvElement.getElementMetaData().getTypeInfo())) {
                    if (mkvElement instanceof MkvStartMasterElement) {
                        String contactIdFromStream = getContactIdFromStreamTag(tagProcessor);
                        if (contactIdFromStream != null && !contactIdFromStream.equals(contactId)) {
                            //expected Connect ContactId does not match the actual ContactId. End the streaming by
                            //returning an empty ByteBuffer
                            return ByteBuffer.allocate(0);
                        }
                        tagProcessor.clear();
                    }
                } else if (MkvTypeInfos.SIMPLEBLOCK.equals(mkvElement.getElementMetaData().getTypeInfo())) {
                    MkvDataElement dataElement = (MkvDataElement) mkvElement;
                    Frame frame = ((MkvValue<Frame>) dataElement.getValueCopy()).getVal();
                    ByteBuffer audioBuffer = frame.getFrameData();
                    long trackNumber = frame.getTrackNumber();
                    MkvTrackMetadata metadata = fragmentVisitor.getMkvTrackMetadata(trackNumber);
                    if (TrackName.AUDIO_FROM_CUSTOMER.getName().equals(metadata.getTrackName())) {
                        return audioBuffer;
                    }
                }
            }
        }

        return ByteBuffer.allocate(0);
    }

    /**
     * Makes a GetMedia call to KVS and retrieves the InputStream corresponding to the given streamName and startFragmentNum
     *
     * @param streamName Stream Name
     * @param region Stream Region
     * @param startFragmentNum Starting Fragment Number when recording started
     * @param awsCredentialsProvider Credential
     * @param startSelectorType Where the stream should start at. See StartSelectorType.
     * @return InputStream
     */
    public static InputStream getInputStreamFromKVS(String streamName,
                                                    Regions region,
                                                    String startFragmentNum,
                                                    AWSCredentialsProvider awsCredentialsProvider,
                                                    String startSelectorType) {
        Validate.notNull(streamName);
        Validate.notNull(region);
        Validate.notNull(startFragmentNum);
        Validate.notNull(awsCredentialsProvider);

        AmazonKinesisVideo amazonKinesisVideo = AmazonKinesisVideoClientBuilder.standard().build();

        String endPoint = amazonKinesisVideo.getDataEndpoint(new GetDataEndpointRequest()
                .withAPIName(APIName.GET_MEDIA)
                .withStreamName(streamName)).getDataEndpoint();

        AmazonKinesisVideoMediaClientBuilder amazonKinesisVideoMediaClientBuilder = AmazonKinesisVideoMediaClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endPoint, region.getName()))
                .withCredentials(awsCredentialsProvider);
        AmazonKinesisVideoMedia amazonKinesisVideoMedia = amazonKinesisVideoMediaClientBuilder.build();

        StartSelector startSelector;
        startSelectorType = isNullOrEmpty(startSelectorType) ? "NOW" : startSelectorType;
        switch (startSelectorType) {
            case "FRAGMENT_NUMBER":
                startSelector = new StartSelector()
                        .withStartSelectorType(StartSelectorType.FRAGMENT_NUMBER)
                        .withAfterFragmentNumber(startFragmentNum);
                logger.info("StartSelector set to FRAGMENT_NUMBER");
                break;
            case "NOW":
            default:
                startSelector = new StartSelector()
                        .withStartSelectorType(StartSelectorType.NOW);
                logger.info("StartSelector set to NOW");
                break;
        }

        GetMediaResult getMediaResult = amazonKinesisVideoMedia.getMedia(new GetMediaRequest()
                .withStreamName(streamName)
                .withStartSelector(startSelector));

        logger.info("GetMedia called on stream {} response {} requestId {}", streamName,
                getMediaResult.getSdkHttpMetadata().getHttpStatusCode(),
                getMediaResult.getSdkResponseMetadata().getRequestId());

        return getMediaResult.getPayload();
    }
}
