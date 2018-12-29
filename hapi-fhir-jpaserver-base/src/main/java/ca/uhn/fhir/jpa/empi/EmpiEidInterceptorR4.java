package ca.uhn.fhir.jpa.empi;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.hl7.fhir.instance.model.Conformance.SearchModifierCode;
import org.hl7.fhir.instance.model.api.IBaseCoding;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Person;
import org.hl7.fhir.r4.model.Person.PersonLinkComponent;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.utilities.json.JSONUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.google.gson.JsonObject;

import ca.uhn.fhir.jpa.dao.DaoMethodOutcome;
import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.StringAndListParam;
import ca.uhn.fhir.rest.param.StringOrListParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.interceptor.ServerOperationInterceptorAdapter;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2018 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
public class EmpiEidInterceptorR4 extends ServerOperationInterceptorAdapter {

    private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(EmpiEidInterceptorR4.class);

    public static final String TAG_DUP_PERSON = "dupPerson";
    // When create a Person, do not search for the matched person, create eid
    // that person
    public static final String TAG_FORCE_EID = "forceEid";

    @Autowired
    @Qualifier("myPatientDaoR4")
    private IFhirResourceDao<Patient> myPatientDao;

    @Autowired
    @Qualifier("myPersonDaoR4")
    private IFhirResourceDao<Person> myPersonDao;

    @Autowired
    @Qualifier("myPractitionerDaoR4")
    private IFhirResourceDao<Practitioner> myPractitionerDao;

    private String myEnterpriseIdentifierSystem;
    private String myTagSystem;

    private String myPersonMatchName;

    // -- all exact match only
    private String myPersonMatchBirthDate;
    private String myPersonMatchGender;
    private String myPersonMatchAddressCity;
    private String myPersonMatchAddressCountry;
    private String myPersonMatchAddressPostalcode;
    private String myPersonMatchAddressState;

    private String myTagMessage;
    
    public EmpiEidInterceptorR4(String theEnterpriseIdentifierSystem, String theTagSystem, String theMatchCriteria) {
        super();

        Validate.notBlank(theEnterpriseIdentifierSystem);
        Validate.notBlank(theTagSystem);
        Validate.notBlank(theMatchCriteria);

        ourLog.info("theEnterpriseIdentifierSystem={}.", theEnterpriseIdentifierSystem);
        this.myEnterpriseIdentifierSystem = theEnterpriseIdentifierSystem;

        ourLog.info("theTagSystem={}.", theTagSystem);
        this.myTagSystem = theTagSystem;

        ourLog.info("thePersonMatchJsonString={}.", theMatchCriteria);
        parseMatchCriteria(theMatchCriteria);

    }

    public void setMyPatientDao(IFhirResourceDao<Patient> myPatientDao) {
        this.myPatientDao = myPatientDao;
    }

    public void setMyPersonDao(IFhirResourceDao<Person> myPersonDao) {
        this.myPersonDao = myPersonDao;
    }

    public void setMyPractitionerDao(IFhirResourceDao<Practitioner> myPractitionerDao) {
        this.myPractitionerDao = myPractitionerDao;
    }

    @Override
    public void resourcePreCreate(RequestDetails theRequest, IBaseResource theResource) {

        if (myPersonDao.getContext().getResourceDefinition(theResource).getName().equals("Person")) {

            Person thePerson = (Person) theResource;
            if (hasTag(thePerson, TAG_FORCE_EID)) {
                // do the force eid logic
                injectEid(thePerson, null); // create new EID
            } else {

                // Duplicate check
                List<IBaseResource> matchedPersonList = getMatchedPerson(thePerson);
                int size = matchedPersonList.size();

                if (size == 0) {
                    injectEid(thePerson, null); // create new EID
                } else {
                    injectTag(thePerson, TAG_DUP_PERSON, myTagMessage);
                }

                // -- inject the tag for all matched person
                for (IBaseResource matchedResouce : matchedPersonList) {
                    Person matchedPerson = (Person) matchedResouce;
                    if (!hasTag(matchedPerson, TAG_DUP_PERSON)) {
                        injectTag(matchedPerson, TAG_DUP_PERSON, myTagMessage);
                        myPersonDao.update(matchedPerson);
                    }
                }
            }
        } else if (myPatientDao.getContext().getResourceDefinition(theResource).getName().equals("Patient")) {

            Patient thePatient = (Patient) theResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getMatchedPerson(thePatient);
            int size = matchedPersonList.size();

            // -- not very efficient, search the person twice, in precreate and
            // created
            // -- how to pass the result over?
            if (size > 1) {
                // found multiple person, inject the tag on person
                injectTag(thePatient, TAG_DUP_PERSON, myTagMessage);
            }
            
        } else if (myPractitionerDao.getContext().getResourceDefinition(theResource).getName().equals("Practitioner")) {

            Practitioner thePractitioner = (Practitioner) theResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getMatchedPerson(thePractitioner);
            int size = matchedPersonList.size();

            // -- not very efficient, search the person twice, in precreate and
            // created
            // -- how to pass the result over?
            if (size > 1) {
                // found multiple person, inject the tag on person
                injectTag(thePractitioner, TAG_DUP_PERSON, myTagMessage);
            }
        }
    }

    @Override
    public void resourceCreated(RequestDetails theRequest, IBaseResource theResource) {

        if (myPatientDao.getContext().getResourceDefinition(theResource).getName().equals("Patient")) {

            Patient thePatient = (Patient) theResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getMatchedPerson(thePatient);
            int size = matchedPersonList.size();

            if (size == 0) {
                // No Person with same name and birth date found from this
                // patient.
                // create the Person, and linked the patient to the person
                createPersonFromPatient(thePatient);
            } else if (size == 1) {
                // Found one person with same name and birth date.
                // link the patient to the person
                Person theMatchedPerson = (Person) matchedPersonList.get(0);
                linkPatientToThePerson(thePatient, theMatchedPerson);
            } else {
                // do nothing, in the preCreated, the dup_person tag is injected
            }
        } else if (myPractitionerDao.getContext().getResourceDefinition(theResource).getName().equals("Practitioner")) {

            Practitioner thePractitioner = (Practitioner) theResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getMatchedPerson(thePractitioner);
            int size = matchedPersonList.size();

            if (size == 0) {
                // No Person with same name and birth date found from this
                // patient.
                // create the Person, and linked the patient to the person
                createPersonFromPractitioner(thePractitioner);
            } else if (size == 1) {
                // Found one person with same name and birth date.
                // link the patient to the person
                Person theMatchedPerson = (Person) matchedPersonList.get(0);
                linkPatientToThePerson(thePractitioner, theMatchedPerson);
            } else {
                // do nothing, in the preCreated, the dup_person tag is injected
            }
        }


    }

    @Override
    public void resourcePreUpdate(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {

        if (myPersonDao.getContext().getResourceDefinition(theNewResource).getName().equals("Person")) {

            // 1. if the new person has EID, do nothing
            Person theNewPerson = (Person) theNewResource;
            if (getEid(theNewPerson) != null)
                return;

            // 2. otherwise, copy the EID from old resource
            Person theOldPerson = (Person) theOldResource;
            String theOldEid = getEid(theOldPerson);
            if (theOldEid != null) {
                // found theOidEid, inject the old Eid to the new patient
                injectEid(theNewPerson, theOldEid);
                return;
            }

            // 3. create new EID if there is no EID from old resource
            injectEid(theNewPerson, null); // inject new EID
        }
    }

    @Override
    public void resourceUpdated(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {

        if (myPatientDao.getContext().getResourceDefinition(theNewResource).getName().equals("Patient")) {

            Patient thePatient = (Patient) theNewResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getPersonByLinkId(thePatient.getId());
            int size = matchedPersonList.size();

            if (size == 0) {
                // The Patient is not linked to and Person
                // create the Person, and linked the patient to the person
                createPersonFromPatient(thePatient);
            } else if (size == 1) {
                // Found one person with linked to this Patient.
                // do nothing
                return;
            } else {
                // Found multiple person linked to this Patient,
                // something wrong
                throw new UnprocessableEntityException("Found multiple matched person linked to the patinet : " + thePatient.getId());
            }
        } else if (myPractitionerDao.getContext().getResourceDefinition(theNewResource).getName().equals("Practitioner")) {

            Practitioner thePractioner = (Practitioner) theNewResource;
            // -- Get matched person list.
            List<IBaseResource> matchedPersonList = getPersonByLinkId(thePractioner.getId());
            int size = matchedPersonList.size();

            if (size == 0) {
                // The Patient is not linked to and Person
                // create the Person, and linked the patient to the person
                createPersonFromPractitioner(thePractioner);
            } else if (size == 1) {
                // Found one person with linked to this Patient.
                // do nothing
                return;
            } else {
                // Found multiple person linked to this Patient,
                // something wrong
                throw new UnprocessableEntityException("Found multiple matched person linked to the patinet : " + thePractioner.getId());
            }
        }
    }

    private List<IBaseResource> getPersonByLinkId(String patientId) {

        SearchParameterMap theParams = new SearchParameterMap();
        theParams.add(Person.SP_LINK, new ReferenceParam(patientId));
        theParams.setLoadSynchronousUpTo(10);

        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());

        return theOtherPatientList;
    }

    // -- NOTE: if the search parameter values are missing, it's a not match
    private List<IBaseResource> getMatchedPerson(IBaseResource theResource) {

        SearchParameterMap theParams = new SearchParameterMap();
        theParams.setLoadSynchronousUpTo(10);

        // -- name
        if (StringUtils.isNotEmpty(myPersonMatchName)) {

            StringAndListParam theNameParams = getName(theResource);
            if (theNameParams != null)
                theParams.add(Person.SP_NAME, theNameParams);
            else
                return Collections.<IBaseResource>emptyList();

            theParams.add(Person.SP_NAME, theNameParams);
        }

        // only support EQ, the other is ignored
        if (StringUtils.isNotEmpty(myPersonMatchBirthDate)) {
            DateParam birthDate = getBirthDate(theResource);
            if (birthDate != null)
                theParams.add(Person.SP_BIRTHDATE, birthDate);
            else
                return Collections.<IBaseResource>emptyList();
        }

        if (StringUtils.isNotEmpty(myPersonMatchGender)) { // only exact match
            TokenParam theGender = getGender(theResource);
            if (theGender != null)
                theParams.add(Person.SP_GENDER, theGender);
            else
                return Collections.<IBaseResource>emptyList();
        }

        // only exact match
        if (StringUtils.isNotEmpty(myPersonMatchAddressCity)) { 
            StringParam theField = getAddressField(theResource, Person.SP_ADDRESS_CITY);
            if (theField != null)
                theParams.add(Person.SP_ADDRESS_CITY, theField);
            else
                return Collections.<IBaseResource>emptyList();
        }

        // only exact match
        if (StringUtils.isNotEmpty(myPersonMatchAddressCountry)) {
            StringParam theField = getAddressField(theResource, Person.SP_ADDRESS_COUNTRY);
            if (theField != null)
                theParams.add(Person.SP_ADDRESS_COUNTRY, theField);
            else
                return Collections.<IBaseResource>emptyList();
        }

        // only exact match
        if (StringUtils.isNotEmpty(myPersonMatchAddressPostalcode)) {
            StringParam theField = getAddressField(theResource, Person.SP_ADDRESS_POSTALCODE);
            if (theField != null)
                theParams.add(Person.SP_ADDRESS_POSTALCODE, theField);
            else
                return Collections.<IBaseResource>emptyList();
        }

        // only exact match
        if (StringUtils.isNotEmpty(myPersonMatchAddressState)) { 
            StringParam theField = getAddressField(theResource, Person.SP_ADDRESS_STATE);
            if (theField != null)
                theParams.add(Person.SP_ADDRESS_STATE, theField);
            else
                return Collections.<IBaseResource>emptyList();
        }
        IBundleProvider provider = myPersonDao.search(theParams);
        List<IBaseResource> theOtherPatientList = provider.getResources(0, provider.size());

        return theOtherPatientList;
    }

    private IIdType createPersonFromPatient(Patient thePatient) {

        String thePatientId = thePatient.getId();

        Person thePerson = new Person();
        thePerson.addName(thePatient.getNameFirstRep());
        thePerson.setBirthDate(thePatient.getBirthDateElement().getValue());
        thePerson.setGender(thePatient.getGender());
        List<Address> thePatientAddressList = thePatient.getAddress();
        for (Address address: thePatientAddressList) {
            thePerson.addAddress(address);
        }

        Identifier theEid = thePerson.addIdentifier();
        theEid.setSystem(myEnterpriseIdentifierSystem);
        theEid.setValue(UUID.randomUUID().toString());

        PersonLinkComponent plc = thePerson.addLink();
        Reference target = plc.getTarget();
        target.setReference(thePatientId);
        target.setDisplay(getLastName(thePatient) + ", " + getFirstName(thePatient));
        plc.setTarget(target);

        DaoMethodOutcome createdPerson = myPersonDao.create(thePerson);

        return createdPerson.getId();
    }

    private IIdType createPersonFromPractitioner(Practitioner thePractitioner) {

        String thePractionerId = thePractitioner.getId();

        Person thePerson = new Person();
        thePerson.addName(thePractitioner.getNameFirstRep());
        thePerson.setBirthDate(thePractitioner.getBirthDateElement().getValue());
        thePerson.setGender(thePractitioner.getGender());
        List<Address> thePractionerAddressList = thePractitioner.getAddress();
        for (Address address: thePractionerAddressList) {
            thePerson.addAddress(address);
        }

        Identifier theEid = thePerson.addIdentifier();
        theEid.setSystem(myEnterpriseIdentifierSystem);
        theEid.setValue(UUID.randomUUID().toString());

        PersonLinkComponent plc = thePerson.addLink();
        Reference target = plc.getTarget();
        target.setReference(thePractionerId);
        target.setDisplay(getLastName(thePractitioner) + ", " + getFirstName(thePractitioner));
        plc.setTarget(target);

        DaoMethodOutcome createdPerson = myPersonDao.create(thePerson);

        return createdPerson.getId();
    }

    private void linkPatientToThePerson(IBaseResource theResource, Person thePerson) {

        String theTargetResourceId = null;
        if (theResource instanceof Patient)             
            theTargetResourceId = ((Patient)theResource).getId();
        else if (theResource instanceof Practitioner)
            theTargetResourceId = ((Practitioner)theResource).getId();

        PersonLinkComponent plc = thePerson.addLink();
        Reference target = new Reference();
        target.setReference(theTargetResourceId);
        target.setDisplay(getLastName(theResource) + ", " + getFirstName(theResource));

        plc.setTarget(target);

        myPersonDao.update(thePerson);

        return;
    }

    private void injectEid(Person thePerson, String eid) {

        // injectEid if it does not exists
        if (getEid(thePerson) == null) {
            Identifier theEid = thePerson.addIdentifier();
            theEid.setSystem(myEnterpriseIdentifierSystem);
            if (eid == null)
                theEid.setValue(UUID.randomUUID().toString());
            else
                theEid.setValue(eid);
        }
    }

    private void injectTag(IBaseResource theResource, String theTagCode, String theTagDisplay) {

        IBaseCoding theTag = theResource.getMeta().addTag();
        theTag.setSystem(myTagSystem);
        theTag.setCode(theTagCode);
        theTag.setDisplay(theTagDisplay);
    }

    private boolean hasTag(IBaseResource theResource, String theTagCode) {

        IBaseCoding theTag = theResource.getMeta().getTag(myTagSystem, theTagCode);
        if (theTag == null)
            return false;
        return true;
    }

    private String getEid(Person thePerson) {

        List<Identifier> identifierList = thePerson.getIdentifier();
        String eid;
        for (Identifier identifier : identifierList) {

            if (!myEnterpriseIdentifierSystem.equals(identifier.getSystem()))
                continue;

            eid = identifier.getValue();
            if (eid == null)
                continue;

            return eid;
        }

        return null;
    }

    private void parseMatchCriteria(String theMatchJsonString) {

        StringBuilder theMessage = new StringBuilder();
        theMessage.append("Same ");
        JsonObject myMatchCriteria = JSONUtil.parse(theMatchJsonString);

        JsonObject thePersonMatchCriteria = myMatchCriteria.get("Person").getAsJsonObject();
        if (thePersonMatchCriteria != null) {
            if (thePersonMatchCriteria.has(Person.SP_NAME)) {
                myPersonMatchName = thePersonMatchCriteria.get(Person.SP_NAME).getAsString();
                theMessage.append("name ");
            }
            if (thePersonMatchCriteria.has(Person.SP_BIRTHDATE)) {
                myPersonMatchBirthDate = thePersonMatchCriteria.get(Person.SP_BIRTHDATE).getAsString();
                theMessage.append("birthdate ");
            }
            if (thePersonMatchCriteria.has(Person.SP_GENDER)) {
                myPersonMatchGender = thePersonMatchCriteria.get(Person.SP_GENDER).getAsString();
                theMessage.append("gender ");
            }

            if (thePersonMatchCriteria.has(Person.SP_ADDRESS_CITY)) {
                myPersonMatchAddressCity = thePersonMatchCriteria.get(Person.SP_ADDRESS_CITY).getAsString();
                theMessage.append("address.city ");
            }

            if (thePersonMatchCriteria.has(Person.SP_ADDRESS_COUNTRY)) {
                myPersonMatchAddressCountry = thePersonMatchCriteria.get(Person.SP_ADDRESS_COUNTRY).getAsString();
                theMessage.append("address.country ");
            }

            if (thePersonMatchCriteria.has(Person.SP_ADDRESS_POSTALCODE)) {
                myPersonMatchAddressPostalcode = thePersonMatchCriteria.get(Person.SP_ADDRESS_POSTALCODE).getAsString();
                theMessage.append("address.postalcode ");
            }

            if (thePersonMatchCriteria.has(Person.SP_ADDRESS_STATE)) {
                myPersonMatchAddressState = thePersonMatchCriteria.get(Person.SP_ADDRESS_STATE).getAsString();
                theMessage.append("address.state ");
            }
            
            theMessage.append("found.");
            myTagMessage = theMessage.toString();
        }
    }

    private StringAndListParam getName(IBaseResource theResource) {

        String lastName = getLastName(theResource);
        String firstName = getFirstName(theResource);

        if (StringUtils.isEmpty(lastName) || StringUtils.isEmpty(firstName))
            return null;

        StringAndListParam theNameParams = new StringAndListParam();
        if (SearchModifierCode.EXACT.toString().equalsIgnoreCase(myPersonMatchName)) {
            return theNameParams.addAnd(new StringOrListParam().addOr(new StringParam(lastName, true)).addOr(new StringParam(firstName, true)));
        } else {
            return theNameParams.addAnd(new StringOrListParam().addOr(new StringParam(lastName, false)).addOr(new StringParam(firstName, false)));
        }

    }

    private String getLastName(IBaseResource theResource) {

        if (theResource instanceof Person) {
            Person thePerson = (Person) theResource;
            HumanName name = thePerson.getNameFirstRep();
            return name.getFamily();
        } else if (theResource instanceof Patient) {
            Patient thePatient = (Patient) theResource;
            HumanName name = thePatient.getNameFirstRep();
            return name.getFamily();
        } else if (theResource instanceof Practitioner) {
            Practitioner thePractitioner = (Practitioner) theResource;
            HumanName name = thePractitioner.getNameFirstRep();
            return name.getFamily();
        }

        return null;
    }

    private String getFirstName(IBaseResource theResource) {

        if (theResource instanceof Person) {
            Person thePerson = (Person) theResource;
            HumanName name = thePerson.getNameFirstRep();
            return name.getGivenAsSingleString();
        } else if (theResource instanceof Patient) {
            Patient thePatient = (Patient) theResource;
            HumanName name = thePatient.getNameFirstRep();
            return name.getGivenAsSingleString();
        } else if (theResource instanceof Practitioner) {
            Practitioner thePractitioner = (Practitioner) theResource;
            HumanName name = thePractitioner.getNameFirstRep();
            return name.getGivenAsSingleString();
        }

        return null;
    }

    private DateParam getBirthDate(IBaseResource theResource) {

        if (theResource instanceof Person) {
            Person thePerson = (Person) theResource;
            if (thePerson.hasBirthDate())
                return new DateParam(thePerson.getBirthDateElement().asStringValue());
            else
                return null;
        } else if (theResource instanceof Patient) {
            Patient thePatient = (Patient) theResource;
            if (thePatient.hasBirthDate())
                return new DateParam(thePatient.getBirthDateElement().asStringValue());
            else
                return null;
        } else if (theResource instanceof Practitioner) {
            Practitioner thePractitioner = (Practitioner) theResource;
            if (thePractitioner.hasBirthDate())
                return new DateParam(thePractitioner.getBirthDateElement().asStringValue());
            else
                return null;
        }

        return null;
    }

    private TokenParam getGender(IBaseResource theResource) {

        TokenParam theGenderToken;
        if (theResource instanceof Person) {
            Person thePerson = (Person) theResource;
            if (thePerson.hasGender()) {
                theGenderToken = new TokenParam(thePerson.getGender().getSystem(), thePerson.getGender().toCode());
                return theGenderToken;
            }
        } else if (theResource instanceof Patient) {
            Patient thePatient = (Patient) theResource;
            if (thePatient.hasGender()) {
                theGenderToken = new TokenParam(thePatient.getGender().getSystem(), thePatient.getGender().toCode());
                return theGenderToken;
            }
        } else if (theResource instanceof Practitioner) {
            Practitioner thePractitioner = (Practitioner) theResource;
            if (thePractitioner.hasGender()) {
                theGenderToken = new TokenParam(thePractitioner.getGender().getSystem(), thePractitioner.getGender().toCode());
                return theGenderToken;
            }
        }

        return null;
    }

    private StringParam getAddressField(IBaseResource theResource, String addressField) {

        Address address = null;
        if (theResource instanceof Person) {
            address = ((Person) theResource).getAddressFirstRep();
        } else if (theResource instanceof Patient) {
            address = ((Patient) theResource).getAddressFirstRep();
        } else if (theResource instanceof Practitioner) {
            address = ((Practitioner) theResource).getAddressFirstRep();        
        } else {
            return null;
        }

        if (Person.SP_ADDRESS_CITY.equals(addressField))
            if (StringUtils.isEmpty(address.getCity()))
                return null;
            else
                return new StringParam(address.getCity(), true);
        else if (Person.SP_ADDRESS_COUNTRY.equals(addressField))
            if (StringUtils.isEmpty(address.getCountry()))
                return null;
            else
                return new StringParam(address.getCountry(), true);
        else if (Person.SP_ADDRESS_POSTALCODE.equals(addressField))
            if (StringUtils.isEmpty(address.getPostalCode()))
                return null;
            else
                return new StringParam(address.getPostalCode(), true);
        else if (Person.SP_ADDRESS_STATE.equals(addressField))
            if (StringUtils.isEmpty(address.getState()))
                return null;
            else
                return new StringParam(address.getState(), true);

        return null;
    }
}
