/*
 * Copyright 2018 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
 */
package io.sailrocket.core.parser;

import java.util.Iterator;

import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingEndEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

import io.sailrocket.core.builders.PhaseBuilder;
import io.sailrocket.core.builders.SimulationBuilder;

class PhasesParser extends AbstractParser<SimulationBuilder, PhaseBuilder.Discriminator> {

    PhasesParser() {
        subBuilders.put("!atOnce", new PhaseParser.AtOnce());
        subBuilders.put("!always", new PhaseParser.Always());
        subBuilders.put("!rampPerSec", new PhaseParser.RampPerSec());
        subBuilders.put("!constantPerSec", new PhaseParser.ConstantPerSec());
    }

    @Override
    public void parse(Iterator<Event> events, SimulationBuilder target) throws ConfigurationParserException {
        parseList(events, target, this::parsePhase);
    }

    private void parsePhase(Iterator<Event> events, SimulationBuilder target) throws ConfigurationParserException {
        ScalarEvent event = expectEvent(events, ScalarEvent.class);
        if (event.getTag() == null) {
            throw new ConfigurationParserException(event, "Phases must be tagged by the type; use one of: " + subBuilders.keySet());
        }
        Parser<PhaseBuilder.Discriminator> phaseBuilder = subBuilders.get(event.getTag());
        if (phaseBuilder == null) {
            throw new ConfigurationParserException(event, "Unknown phase type: " + event.getTag() + ", expected one of " + subBuilders.keySet());
        }
        String name = event.getValue();
        phaseBuilder.parse(events, target.addPhase(name));
        expectEvent(events, MappingEndEvent.class);
    }
}