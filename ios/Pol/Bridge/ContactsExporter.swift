import Contacts
import Foundation

/// Optional: copies Android contacts into the iPhone address book
/// so names also show in the Phone app and in search.
enum ContactsExporter {
    enum Result { case done(added: Int), denied, failed }

    static func export(_ contacts: [Contact]) async -> Result {
        let store = CNContactStore()
        let granted = (try? await store.requestAccess(for: .contacts)) ?? false
        guard granted else { return .denied }

        // Numbers already on the iPhone, so we never duplicate.
        var existing = Set<String>()
        let request = CNContactFetchRequest(keysToFetch: [CNContactPhoneNumbersKey as CNKeyDescriptor])
        do {
            try store.enumerateContacts(with: request) { c, _ in
                for n in c.phoneNumbers { existing.insert(PhoneNumber.key(n.value.stringValue)) }
            }
        } catch { return .failed }

        let save = CNSaveRequest()
        var added = 0
        for c in contacts {
            let fresh = c.phones.filter { !existing.contains(PhoneNumber.key($0)) }
            guard !fresh.isEmpty else { continue }
            let new = CNMutableContact()
            new.givenName = c.name
            new.phoneNumbers = fresh.map { CNLabeledValue(label: CNLabelPhoneNumberMobile, value: CNPhoneNumber(stringValue: $0)) }
            save.add(new, toContainerWithIdentifier: nil)
            fresh.forEach { existing.insert(PhoneNumber.key($0)) }
            added += 1
        }
        guard added > 0 else { return .done(added: 0) }
        do { try store.execute(save) } catch { return .failed }
        return .done(added: added)
    }
}
