package uk.gov.di.ipv.cri.address.api.handler;

import uk.gov.di.ipv.cri.address.library.exception.AddressProcessingException;
import uk.gov.di.ipv.cri.common.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.cri.common.library.persistence.item.CanonicalAddress;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public class AddressDateLinker {

    @ExcludeFromGeneratedCoverageReport
    private AddressDateLinker() {
        throw new IllegalStateException("Static Utility class");
    }

    public static void linkCurrentAndPreviousAddressesDates(List<CanonicalAddress> addresses)
            throws AddressProcessingException {

        if (addresses.size() > 2) {
            // A non-null validFrom only exists in the first (CURRENT) address
            throw new AddressProcessingException("Too many Addresses.");
        }

        CanonicalAddress prevProcessedAddress = null;

        for (CanonicalAddress address : addresses) {
            if (Objects.isNull(prevProcessedAddress)) {
                // Do nothing to the first address (CURRENT)
                prevProcessedAddress = address;
            } else {
                // Link the newer addresses start date (ValidFrom) to the older addresses end date
                // (ValidUntil)
                LocalDate validFrom = prevProcessedAddress.getValidFrom();

                // ValidUntil must be a date in the past.
                address.setValidUntil(validFrom.minusDays(1));
            }
        }
    }
}
