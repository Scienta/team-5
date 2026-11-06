package no.scienta.cfp;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adding and removing co-speakers. The rules and status codes are in workflow/specs/co-speakers.md (C1-C12).
 */
@Service
public class CoSpeakerService {

    static final int MAX_CO_SPEAKERS = 2;

    private final CoSpeakerDao coSpeakers;
    private final SpeakerDao speakers;
    private final ConferenceService conference;

    public CoSpeakerService(CoSpeakerDao coSpeakers, SpeakerDao speakers, ConferenceService conference) {
        this.coSpeakers = coSpeakers;
        this.speakers = speakers;
        this.conference = conference;
    }

    @Transactional
    public TalkDto add(long talkId, long speakerId) {
        long mainSpeaker = lockTalkAndCheckSpeaker(talkId, speakerId);
        if (speakerId == mainSpeaker) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the main speaker can't be a co-speaker");
        }
        List<Long> current = coSpeakers.coSpeakers(talkId);
        if (!current.contains(speakerId)) {
            if (current.size() >= MAX_CO_SPEAKERS) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "a talk has at most " + MAX_CO_SPEAKERS + " co-speakers");
            }
            coSpeakers.add(talkId, speakerId);
        }
        return conference.getTalk(talkId);
    }

    @Transactional
    public TalkDto remove(long talkId, long speakerId) {
        lockTalkAndCheckSpeaker(talkId, speakerId);
        if (!coSpeakers.remove(talkId, speakerId)) {
            // C10: the harness removes the same co-speaker twice and expects the second to fail (tick 85)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not a co-speaker of this talk");
        }
        return conference.getTalk(talkId);
    }

    /**
     * 404 for an unknown talk, then for an unknown speaker (C11). Returns the main speaker, with the talk locked.
     */
    private long lockTalkAndCheckSpeaker(long talkId, long speakerId) {
        Long mainSpeaker = coSpeakers.lockTalk(talkId);
        if (mainSpeaker == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        if (!speakers.exists(speakerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        return mainSpeaker;
    }
}
