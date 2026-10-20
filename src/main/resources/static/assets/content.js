/** Product copy is separate from application behavior so operators can review it easily. */
export const content = {
  help: {
    title: 'A smoother way to reserve', eyebrow: 'THE VOLTWAY GUIDE',
    sections: [
      { title: '1. Find your station', body: 'Search by station name or location. The availability shown is the station capacity recorded by Voltway. Refresh to get the latest information before you reserve.' },
      { title: '2. Reserve available capacity', body: 'Sign in, choose a station and review the reservation details. A successful confirmation reserves one available space. Availability is checked again when your request reaches the server.' },
      { title: '3. Manage your booking', body: 'Find your confirmation in My bookings. You can cancel an active reservation, and the space becomes available again. An operator marks a reservation complete when the session has finished.' },
      { title: 'What a reservation includes', body: 'A reservation records charging capacity in this application. It does not start a physical charger, measure energy use or guarantee a particular connector. Check the station location and confirm connector compatibility with the operator before travelling.' },
      { title: 'Need to change your account?', body: 'Open Account to update your name or email address and to change your password. Changing your password signs you out so you can sign in with your new password.' }
    ]
  },
  privacy: {
    title: 'Your information, clearly explained', eyebrow: 'PRIVACY & COOKIES',
    sections: [
      { title: 'What the application stores', body: 'Voltway stores your name, email address, a password hash, reservations and in-app notifications. Your password is never returned by the account APIs. Operators can view customer account details needed to manage reservations.' },
      { title: 'Essential session cookies', body: 'The application uses an essential session cookie to keep you signed in and a security token to protect account changes. The frontend does not store your password or authentication tokens in browser local storage.' },
      { title: 'Your reservations', body: 'Reservation history is retained to support station operations. Account details and booking records are served from this application’s backend, rather than saved only in your browser.' },
      { title: 'Contact and retention policy', body: 'The hosting operator is responsible for its published contact details, retention periods and applicable privacy obligations. These operational details must be configured by the operator before offering this service publicly.' }
    ]
  },
  terms: {
    title: 'Know before you reserve', eyebrow: 'BOOKING GUIDE',
    sections: [
      { title: 'Availability can change', body: 'The number of available spaces is a current server snapshot. A reservation is confirmed only after the server successfully creates it. If another driver takes the final space first, you will see a clear availability error.' },
      { title: 'One reservation, one space', body: 'Each confirmed reservation uses one space at the selected station. An active reservation remains in place until it is cancelled or completed by an operator. This version does not offer future time slots or timed expiry.' },
      { title: 'Charges and payments', body: 'There is no online reservation charge. Voltway collects no payment or bank card details. Any physical charging costs and on-site payment arrangements must be confirmed directly with the station operator.' },
      { title: 'Cancellation and completion', body: 'Cancel a reservation from My bookings when your plans change. Repeating a cancellation does not release an additional space. Completed and cancelled reservations stay in your history.' },
      { title: 'Station operation', body: 'Voltway manages reservations. The station operator is responsible for the physical equipment, opening hours, safety, compatible vehicles and any on-site charging agreement.' }
    ]
  }
};

/** A replay can return an existing terminal booking, so success copy follows its actual status. */
export function reservationFeedback(status) {
  const active = status === 'Confirmed';
  return {
    active, status,
    eyebrow: active ? 'YOU’RE ALL SET' : 'RESERVATION HISTORY',
    title: active ? 'Your space is reserved.' : status === 'Cancelled' ? 'Your reservation is cancelled.' : status === 'Completed' ? 'Your reservation is complete.' : 'Your reservation details',
    description: active ? 'A little less searching. A little more going.' : 'The latest reservation status is shown below. Find its full details in your booking history.'
  };
}

/** An explicit server boolean controls demo copy; arbitrary configuration is never rendered. */
export function publicDemoNotice(siteInfo) {
  return siteInfo?.demo === true && typeof siteInfo.notice === 'string' ? siteInfo.notice : '';
}

export function siteContent(key, siteInfo) {
  const item = content[key];
  if (!item || siteInfo?.demo !== true) return item;
  const demoSection = {
    title: 'This is a public demonstration',
    body: 'Use invented test names and email addresses and a unique test password. Do not submit personal, payment or sensitive information. There are no online charges and this website does not activate physical charging equipment. Demo data can reset, and the free database can expire.'
  };
  const sections = item.sections.map(section => {
    if (key === 'privacy' && section.title === 'Your reservations') return {
      ...section, body: 'Demo accounts, reservations and in-app notifications are stored in the separate hosted demonstration database. Records may be reset or become unavailable if the free database expires. Do not rely on this demo for lasting records or real charging arrangements.'
    };
    if (key === 'privacy' && section.title === 'Contact and retention policy') return {
      title: 'Operator details and retention',
      body: 'No dedicated support contact or retention guarantee is published inside this demonstration. The project owner manages the hosted service and may reset or remove demo records. Use test details only.'
    };
    if (key === 'help' && section.title === 'What a reservation includes') return {
      ...section, body: 'A demo reservation records one space in this application only. It does not start a physical charger, measure energy, guarantee a connector or arrange a real charging session. Use it to try the reservation workflow.'
    };
    if (key === 'terms' && section.title === 'Charges and payments') return {
      ...section, body: 'The public demonstration collects no reservation charge, charging payment or bank card details. Do not submit payment information or use demo records as a real charging agreement.'
    };
    if (key === 'terms' && section.title === 'Station operation') return {
      ...section, body: 'The public demonstration manages application records only. No physical station is controlled or activated by this website. Demo station records and reservations are for testing the workflow.'
    };
    return section;
  });
  return { ...item, sections: [demoSection, ...sections] };
}
