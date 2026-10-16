package top.hcode.hoj.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.hcode.hoj.manager.oj.SiteRatingManager;

@Component
@Slf4j
public class SiteRatingJob {
    @Autowired private SiteRatingManager ratings;
    @Scheduled(initialDelay = 10000, fixedDelay = 60000)
    public void refresh() {
        try { ratings.rebuild(); }
        catch (Exception e) { log.error("Site rating refresh failed; retaining the previous snapshot", e); }
    }
}
