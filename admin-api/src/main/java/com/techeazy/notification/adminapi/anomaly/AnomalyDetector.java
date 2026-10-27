/*
 * Copyright 2026 Vasantha Kumar Kesavan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi.anomaly;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Scores every tenant's measured hour against its own history and records what stands out (ADR-039). */
class AnomalyDetector {

    private final TrafficReader reader;
    private final BaselineScorer scorer;
    private final AnomalyStore store;
    private final Clock clock;
    private final int baselineWeeks;

    AnomalyDetector(TrafficReader reader, BaselineScorer scorer, AnomalyStore store, Clock clock, int baselineWeeks) {
        this.reader = reader;
        this.scorer = scorer;
        this.store = store;
        this.clock = clock;
        this.baselineWeeks = baselineWeeks;
    }

    /**
     * Scores one measured hour.
     *
     * @param hour     the start of the hour, already measured
     * @param features which features to score
     * @return the anomalies recorded by this call; ones recorded earlier are not repeated
     */
    List<Anomaly> detect(Instant hour, Set<Feature> features) {
        List<Anomaly> found = new ArrayList<>();
        for (TrafficHour current : reader.hour(hour)) {
            TenantHistory history = reader.history(current.clientId(), current.channel(), hour, baselineWeeks);
            found.addAll(scorer.score(current, history, features));
        }
        return store.record(found, clock.instant());
    }
}
