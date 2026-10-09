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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.time.DateUtils;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.Encounter;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Relationship;
import org.openmrs.Visit;
import org.openmrs.api.ConceptService;
import org.openmrs.api.PersonService;
import org.openmrs.contrib.testdata.TestDataManager;
import org.openmrs.module.emrapi.EmrApiProperties;
import org.openmrs.module.emrapi.concept.EmrConceptService;
import org.openmrs.module.emrapi.descriptor.MissingConceptException;
import org.openmrs.module.emrapi.disposition.DispositionDescriptor;
import org.openmrs.module.emrapi.disposition.DispositionService;
import org.openmrs.module.emrapi.disposition.DispositionType;
import org.openmrs.module.emrapi.maternal.MaternalService;
import org.openmrs.module.emrapi.maternal.MotherAndChild;
import org.openmrs.module.emrapi.maternal.MothersAndChildrenSearchCriteria;
import org.openmrs.module.emrapi.test.ContextSensitiveMetadataTestUtils;
import org.openmrs.test.jupiter.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

public class InpatientAdmissionMetadataDependencyTest extends BaseModuleContextSensitiveTest {

	@Autowired
	private AdtService adtService;

	@Autowired
	private MaternalService maternalService;

	@Autowired
	private DispositionService dispositionService;

	@Autowired
	private EmrApiProperties emrApiProperties;

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private EmrConceptService emrConceptService;

	@Autowired
	private PersonService personService;

	@Autowired
	private TestDataManager testDataManager;

	@BeforeEach
	public void setup() {
		executeDataSet("baseTestDataset.xml");
		// These installations can record visits and relationships without inpatient disposition metadata.
		assertThrows(MissingConceptException.class, dispositionService::getDispositionDescriptor);
	}

	@Test
	public void shouldReturnNoAdmissionsWithoutDispositionMetadata() {
		assertTrue(adtService.getInpatientAdmissions(new InpatientAdmissionSearchCriteria()).isEmpty());
	}

	@Test
	public void shouldReturnNoAdmissionsWhenEveryAdmissionIsFilteredOut() {
		Visit visit = createVisit(testDataManager.randomPatient().save());
		admit(visit);
		InpatientAdmissionSearchCriteria criteria = new InpatientAdmissionSearchCriteria();
		criteria.addCurrentInpatientLocation(testDataManager.location().name("Other ward").save());
		assertTrue(adtService.getInpatientAdmissions(criteria).isEmpty());
	}

	@Test
	public void shouldKeepTheCurrentRequestForAnAdmission() {
		dispositionService.setDispositionConfig("testDispositionConfig.json");
		DispositionDescriptor descriptor = ContextSensitiveMetadataTestUtils.setupDispositionDescriptor(conceptService,
		    dispositionService);
		ContextSensitiveMetadataTestUtils.setupAdmissionDecisionConcept(conceptService, emrApiProperties);
		Visit visit = createVisit(testDataManager.randomPatient().save());
		admit(visit);
		Encounter request = testDataManager.encounter().patient(visit.getPatient()).visit(visit)
		        .encounterType(emrApiProperties.getVisitNoteEncounterType()).location(visit.getLocation())
		        .encounterDatetime(DateUtils.addHours(visit.getStartDatetime(), 2)).save();
		Obs requestGroup = testDataManager.obs().encounter(request).concept(descriptor.getDispositionSetConcept())
		        .member(testDataManager.obs().encounter(request).concept(descriptor.getDispositionConcept())
		                .value(emrConceptService.getConcept("org.openmrs.module.emrapi:Discharged")).get())
		        .save();
		InpatientAdmissionSearchCriteria criteria = new InpatientAdmissionSearchCriteria();
		criteria.addVisitId(visit.getVisitId());

		List<InpatientAdmission> admissions = adtService.getInpatientAdmissions(criteria);

		assertEquals(1, admissions.size());
		assertEquals(visit, admissions.get(0).getVisit());
		assertNotNull(admissions.get(0).getCurrentInpatientRequest());
		assertEquals(requestGroup, admissions.get(0).getCurrentInpatientRequest().getDispositionObsGroup());
		assertEquals(DispositionType.DISCHARGE, admissions.get(0).getCurrentInpatientRequest().getDispositionType());
	}

	@Test
	public void shouldStillRequireDispositionMetadataForAnAdmission() {
		Visit visit = createVisit(testDataManager.randomPatient().save());
		admit(visit);
		InpatientAdmissionSearchCriteria criteria = new InpatientAdmissionSearchCriteria();
		criteria.addVisitId(visit.getVisitId());

		assertThrows(MissingConceptException.class, () -> adtService.getInpatientAdmissions(criteria));
	}

	@Test
	public void shouldReadAnOutpatientChildByMotherWithoutDispositionMetadata() {
		assertOutpatientRelationship(true);
	}

	@Test
	public void shouldReadAnOutpatientMotherByChildWithoutDispositionMetadata() {
		assertOutpatientRelationship(false);
	}

	private Visit createVisit(Patient patient) {
		Location location = testDataManager.location().name("Visit location " + patient.getUuid()).tag("Visit Location").save();
		return testDataManager.visit().patient(patient).visitType(emrApiProperties.getAtFacilityVisitType())
		        .location(location).started(new DateTime(2020, 10, 30, 0, 0).toDate()).save();
	}

	private void admit(Visit visit) {
		testDataManager.encounter().patient(visit.getPatient()).visit(visit)
		        .encounterType(emrApiProperties.getAdmissionEncounterType()).location(visit.getLocation())
		        .encounterDatetime(DateUtils.addHours(visit.getStartDatetime(), 1)).save();
	}

	private void assertOutpatientRelationship(boolean queryByMother) {
		Patient mother = testDataManager.randomPatient().birthdate("1980-01-01").gender("F").save();
		Patient child = testDataManager.randomPatient().birthdate("2019-01-01").save();
		createVisit(mother);
		createVisit(child);
		Relationship relationship = new Relationship();
		relationship.setPersonA(mother);
		relationship.setPersonB(child);
		relationship.setRelationshipType(emrApiProperties.getMotherChildRelationshipType());
		personService.saveRelationship(relationship);
		MothersAndChildrenSearchCriteria criteria = new MothersAndChildrenSearchCriteria(
		        queryByMother ? Collections.singletonList(mother.getUuid()) : null,
		        queryByMother ? null : Collections.singletonList(child.getUuid()), false, false, false);

		List<MotherAndChild> links = maternalService.getMothersAndChildren(criteria);

		assertEquals(1, links.size());
		assertEquals(mother, links.get(0).getMother());
		assertEquals(child, links.get(0).getChild());
		assertNull(links.get(0).getMotherAdmission());
		assertNull(links.get(0).getChildAdmission());
	}
}
