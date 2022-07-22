package uk.gov.di.ipv.cri.address.api.handler;

import org.junit.jupiter.api.Test;
import uk.gov.di.ipv.cri.address.library.exception.AddressProcessingException;
import uk.gov.di.ipv.cri.common.library.domain.personidentity.Address;
import uk.gov.di.ipv.cri.common.library.domain.personidentity.AddressType;
import uk.gov.di.ipv.cri.common.library.persistence.item.CanonicalAddress;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddressDateLinkerTest {

    @Test
    void testAddressesLinkerSingleAddress() throws AddressProcessingException {

        LocalDate date = LocalDate.of(2013, 8, 9);

        CanonicalAddress currentAddress = new CanonicalAddress();
        currentAddress.setValidFrom(date);

        List<CanonicalAddress> canonicalAddresses = List.of(currentAddress);

        AddressDateLinker.linkCurrentAndPreviousAddressesDates(canonicalAddresses);

        // Convert CanonicalAddress to common-lib Address
        List<Address> personIdentityAddresses = new ArrayList<>();

        for (CanonicalAddress canonicalAddress : canonicalAddresses) {

            Address personIdentityAddress = ConvertCanonicalAddressToAddress(canonicalAddress);

            personIdentityAddresses.add(personIdentityAddress);
        }

        // Test the AddressType Evaluation
        assertEquals(AddressType.CURRENT, personIdentityAddresses.get(0).getAddressType());
        assertEquals(date, canonicalAddresses.get(0).getValidFrom());
    }

    @Test
    void testAddressesLinkerTwoAddresses() throws AddressProcessingException {

        CanonicalAddress currentAddress = new CanonicalAddress();
        currentAddress.setValidFrom(LocalDate.now());

        CanonicalAddress previousAddress = new CanonicalAddress();
        previousAddress.setValidFrom(null);
        previousAddress.setValidUntil(null);

        List<CanonicalAddress> canonicalAddresses = List.of(currentAddress, previousAddress);

        AddressDateLinker.linkCurrentAndPreviousAddressesDates(canonicalAddresses);

        // Convert CanonicalAddress to common-lib Address
        List<Address> personIdentityAddresses = new ArrayList<>();

        for (CanonicalAddress canonicalAddress : canonicalAddresses) {

            Address personIdentityAddress = ConvertCanonicalAddressToAddress(canonicalAddress);

            personIdentityAddresses.add(personIdentityAddress);
        }

        // Test the AddressType Evaluations
        assertEquals(AddressType.CURRENT, personIdentityAddresses.get(0).getAddressType());
        assertEquals(AddressType.PREVIOUS, personIdentityAddresses.get(1).getAddressType());
    }

    @Test
    void testAddressesLinkerThreeAddresses() {

        CanonicalAddress currentAddress = new CanonicalAddress();
        currentAddress.setValidFrom(LocalDate.now());

        CanonicalAddress previousAddress1 = new CanonicalAddress();
        previousAddress1.setValidFrom(null);
        previousAddress1.setValidUntil(null);

        CanonicalAddress previousAddress2 = new CanonicalAddress();
        previousAddress2.setValidFrom(null);
        previousAddress2.setValidUntil(null);

        List<CanonicalAddress> canonicalAddresses =
                List.of(currentAddress, previousAddress1, previousAddress2);

        assertThrows(
                AddressProcessingException.class,
                () -> AddressDateLinker.linkCurrentAndPreviousAddressesDates(canonicalAddresses));
    }

    private Address ConvertCanonicalAddressToAddress(CanonicalAddress canonicalAddress) {
        Address personIdentityAddress = new Address();
        personIdentityAddress.setValidUntil(canonicalAddress.getValidUntil());
        personIdentityAddress.setValidFrom(canonicalAddress.getValidFrom());

        return personIdentityAddress;
    }
}
