package ru.agimate.controlapi.service.llm;

import ru.agimate.controlapi.database.enums.MediaTransportType;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;
import ru.agimate.controlapi.service.llm.MediaInferenceHttp.Usage;

import java.util.List;

/**
 * One dialect of «ask the provider for a picture». Implementations differ in the endpoint, the shape
 * of the body and where the result lives in the answer; the choice between them belongs to the
 * provider and is made by {@link MediaTransportRegistry} (see {@code docs/decisions/media-transport.md}).
 *
 * <p>A transport returns <b>bytes</b>, never a link: the ones whose provider answers with a URL
 * download it themselves, so the question «who may follow an external link» is settled inside the
 * transport and does not spread into {@link MediaInferenceService} or the file layer.
 */
public interface MediaTransport {

    MediaTransportType type();

    /**
     * @throws MediaInferenceException the provider refused, timed out, or answered in a shape this
     *                                 dialect cannot read
     */
    GeneratedImage generate(GenerationRequest request);

    /**
     * An input picture already read into memory. The transports differ in how they wrap it (a data
     * URI in a chat message, bare base64 in a media body), so the raw bytes travel here.
     */
    record InputImage(String mime, byte[] bytes) {
    }

    /**
     * @param sources empty — generation from scratch, one — editing, several — composition, in list order
     */
    record GenerationRequest(ResolvedLlm resolved, String prompt, List<InputImage> sources) {
    }

    /**
     * @param bytes {@code null} — the model answered without a picture; then {@code text} carries its
     *              reply and that is a result, not an error
     * @param usage {@code null} — the provider reported no token usage (media endpoints bill in money,
     *              not tokens)
     */
    record GeneratedImage(byte[] bytes, String mime, String text, Usage usage) {

        public static GeneratedImage refusal(String text, Usage usage) {
            return new GeneratedImage(null, null, text, usage);
        }
    }
}
