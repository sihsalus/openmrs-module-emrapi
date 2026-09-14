/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.emrapi.adt;

import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import org.apache.commons.lang.time.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptName;
import org.openmrs.Location;
import org.openmrs.LocationTag;
import org.openmrs.Visit;
import org.openmrs.api.ValidationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.emrapi.EmrApiConstants;
import org.openmrs.module.queue.api.QueueEntryService;
import org.openmrs.module.queue.api.QueueService;
import org.openmrs.module.queue.api.search.QueueEntrySearchCriteria;
import org.openmrs.module.queue.model.Queue;
import org.openmrs.module.queue.model.QueueEntry;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses real OpenMRS services, Queue validators/save handlers and Hibernate/H2. */
public class QueueInactiveVisitClosureTest extends BaseModuleContextSensitiveTest {

	@Autowired
	private AdtService adtService;

	@Autowired
	@Qualifier("queue.QueueEntryService")
	private QueueEntryService queueEntryService;

	@Autowired
	@Qualifier("queue.QueueService")
	private QueueService queueService;

	private Location location;

	private Concept option;

	private Date referenceTime;

	@BeforeEach
	public void setUp() throws Exception {
		executeDataSet("baseTestDataset.xml");
		referenceTime = DateUtils.setMilliseconds(new Date(), 0);
		Context.getAdministrationService().setGlobalProperty(EmrApiConstants.GP_VISIT_EXPIRE_HOURS, "24");
		Context.getAdministrationService().setGlobalProperty(EmrApiConstants.GP_INPATIENT_VISIT_EXPIRE_HOURS, "");
		Context.getAdministrationService().setGlobalProperty(
		    EmrApiConstants.GP_USE_CURRENT_TIME_FOR_AUTOMATIC_VISIT_CLOSURE, "true");
		LocationTag tag = Context.getLocationService().getLocationTagByName(EmrApiConstants.LOCATION_TAG_SUPPORTS_VISITS);
		if (tag == null) {
			tag = Context.getLocationService().saveLocationTag(new LocationTag(
			    EmrApiConstants.LOCATION_TAG_SUPPORTS_VISITS, "Synthetic visit location"));
		}
		location = new Location();
		location.setName("Synthetic queue closure location");
		location.addTag(tag);
		location = Context.getLocationService().saveLocation(location);
		option = concept("Synthetic service status priority");
		Concept allowed = concept("Synthetic allowed queue values");
		allowed.setSet(true);
		allowed.addSetMember(option);
		Context.getConceptService().saveConcept(allowed);
		for (String property : new String[] { "queue.serviceConceptSetName", "queue.priorityConceptSetName",
		        "queue.statusConceptSetName" }) {
			Context.getAdministrationService().setGlobalProperty(property, allowed.getUuid());
		}
	}

	@Test
	public void shouldCloseVisitAndActiveQueueWithoutRewritingHistoryOrClosingTodaysVisit() {
		Visit stale = visit(-48);
		Queue queue = queue();
		QueueEntry history = entry(stale, queue, -47, -46);
		Date historicalEnd = history.getEndedAt();
		QueueEntry active = entry(stale, queue, -45, null);
		Visit recent = visit(-1);
		QueueEntry recentEntry = entry(recent, queue(), -1, null);
		Context.flushSession();
		Date before = DateUtils.setMilliseconds(new Date(), 0);
		adtService.closeInactiveVisits();
		Context.flushSession();
		Context.clearSession();
		Visit closed = Context.getVisitService().getVisit(stale.getId());
		QueueEntry ended = queueEntryService.getQueueEntryById(active.getId()).get();
		assertFalse(closed.getStopDatetime().before(before));
		assertFalse(closed.getStopDatetime().after(new Date()));
		assertEquals(closed.getStopDatetime(), ended.getEndedAt());
		assertTrue(ended.getEndedAt().after(ended.getStartedAt()));
		assertEquals(historicalEnd, queueEntryService.getQueueEntryById(history.getId()).get().getEndedAt());
		assertNull(Context.getVisitService().getVisit(recent.getId()).getStopDatetime());
		assertNull(queueEntryService.getQueueEntryById(recentEntry.getId()).get().getEndedAt());
		QueueEntrySearchCriteria search = new QueueEntrySearchCriteria();
		search.setVisit(closed);
		search.setIsEnded(false);
		assertTrue(queueEntryService.getQueueEntries(search).isEmpty());
		Date closedAt = closed.getStopDatetime();
		adtService.closeInactiveVisits();
		Context.flushSession();
		Context.clearSession();
		assertEquals(closedAt, Context.getVisitService().getVisit(stale.getId()).getStopDatetime());
		assertEquals(closedAt, queueEntryService.getQueueEntryById(active.getId()).get().getEndedAt());
	}

	@Test
	public void shouldReproduceTheLegacyFailureWithAQueueStartingAfterTheLastActivity() {
		Context.getAdministrationService().setGlobalProperty(
		    EmrApiConstants.GP_USE_CURRENT_TIME_FOR_AUTOMATIC_VISIT_CLOSURE, "false");
		entry(visit(-48), queue(), -47, null);
		Context.flushSession();
		assertThrows(ValidationException.class, () -> adtService.closeInactiveVisits());
	}

	private Visit visit(int hoursAgo) {
		Visit visit = new Visit(Context.getPatientService().getPatient(7),
		    Context.getVisitService().getVisitType(1), DateUtils.addHours(referenceTime, hoursAgo));
		visit.setLocation(location);
		return Context.getVisitService().saveVisit(visit);
	}

	private Queue queue() {
		Queue queue = new Queue();
		queue.setName("Synthetic queue " + UUID.randomUUID());
		queue.setLocation(location);
		queue.setService(option);
		return queueService.saveQueue(queue);
	}

	private QueueEntry entry(Visit visit, Queue queue, int startHours, Integer endHours) {
		QueueEntry entry = new QueueEntry();
		entry.setVisit(visit);
		entry.setPatient(visit.getPatient());
		entry.setQueue(queue);
		entry.setPriority(option);
		entry.setStatus(option);
		entry.setStartedAt(DateUtils.addHours(referenceTime, startHours));
		if (endHours != null) {
			entry.setEndedAt(DateUtils.addHours(referenceTime, endHours));
		}
		return queueEntryService.saveQueueEntry(entry);
	}

	private Concept concept(String name) {
		Concept concept = new Concept();
		concept.addName(new ConceptName(name, Locale.ENGLISH));
		concept.setDatatype(Context.getConceptService().getConceptDatatypeByName("N/A"));
		concept.setConceptClass(Context.getConceptService().getConceptClassByName("Misc"));
		return Context.getConceptService().saveConcept(concept);
	}
}
