import { request, resetCsrf, ApiError } from './api.js';
import { publicDemoNotice, siteContent, reservationFeedback } from './content.js';

const $ = (selector, context = document) => context.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
const icon = name => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const initials = name => String(name || 'V').trim().split(/\s+/).slice(0, 2).map(part => part[0] || '').join('').toUpperCase();
const isAdmin = () => state.user?.role === 'ADMIN';
const available = station => Number(station.avaialableSlots ?? station.availableSlots ?? 0);
const statusClass = status => status === 'Cancelled' ? 'cancelled' : status === 'Completed' ? 'completed' : '';
const statusBadge = status => `<span class="booking-status ${statusClass(status)}">${status === 'Confirmed' ? icon('check') : ''}${esc(status)}</span>`;
const formatDate = value => {
  const date = value ? new Date(value) : null;
  return date && !Number.isNaN(date.getTime()) ? date.toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : 'Date unavailable';
};
const state = { user: null, stations: [], bookings: [], notifications: [], users: [], siteInfo: { demo: false, notice: '' }, view: 'discover', query: '', availableOnly: false, sort: 'name', bookingFilter: 'All', errors: {}, loading: false, usersLoaded: false };
let bookingRequest;
let dataGeneration = 0;
let sessionGeneration = 0;
let sessionExpiryHandled = false;
let siteInfoReady = Promise.resolve();
const dialog = $('#dialog');

function renderDemoNotice() {
  const notice = publicDemoNotice(state.siteInfo);
  const banner = $('#site-notice');
  banner.hidden = !notice;
  banner.textContent = notice;
  renderDialogDemoNotice();
}

function renderDialogDemoNotice() {
  $('[data-demo-notice]', dialog)?.remove();
  const notice = publicDemoNotice(state.siteInfo);
  if (!notice || !['auth', 'reserve'].includes(dialog.dataset.kind)) return;
  const heading = $('#dialog-title', dialog);
  if (!heading) return;
  const note = document.createElement('p');
  note.className = 'public-demo-note';
  note.dataset.demoNotice = '';
  note.setAttribute('role', 'note');
  note.textContent = notice;
  heading.after(note);
}

async function loadSiteInfo() {
  try { state.siteInfo = await request('/api/site-info', { silent: true, timeoutMs: 2000 }); }
  catch { /* Optional visitor information must not prevent the application from working. */ }
  renderDemoNotice();
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.className = `toast${error ? ' error' : ''}`;
  item.innerHTML = `${icon(error ? 'help' : 'check')}<span>${esc(message)}</span><button aria-label="Dismiss notification">${icon('close')}</button>`;
  $('button', item).addEventListener('click', () => item.remove());
  $('#toast-region').append(item);
  setTimeout(() => item.remove(), error ? 12000 : 6500);
}

function emptyState(title, text, action = '', iconName = 'pin') {
  return `<div class="empty-state"><div class="empty-icon">${icon(iconName)}</div><h3>${esc(title)}</h3><p>${esc(text)}</p>${action}</div>`;
}

function errorPanel(message, action = 'refresh') {
  return `<div class="error-panel" role="alert"><p>${esc(message)}</p><button class="button secondary small" data-action="${action}">${icon('refresh')}Try again</button></div>`;
}

function renderNavigation() {
  const links = [['discover', 'pin', 'Discover stations']];
  if (state.user) links.push(['bookings', 'ticket', isAdmin() ? 'All bookings' : 'My bookings']);
  if (isAdmin()) links.push(['stations', 'grid', 'Manage stations'], ['customers', 'users', 'Customers']);
  if (state.user) links.push(['account', 'user', 'Account']);
  $('#main-nav').innerHTML = links.map(([view, symbol, label]) => `<a class="nav-link" href="#${view}" ${state.view === view ? 'aria-current="page"' : ''} title="${esc(label)}" aria-label="${esc(label)}">${icon(symbol)}<span>${esc(label)}</span></a>`).join('');
  const unread = state.notifications.filter(item => !item.read).length;
  $('#topbar-actions').innerHTML = state.user
    ? `<button class="icon-button" data-action="inbox" aria-label="Notifications${unread ? `, ${unread} unread` : ''}">${icon('bell')}${unread ? '<span class="notification-dot"></span>' : ''}</button><button class="topbar-user" data-action="account" aria-label="Account settings for ${esc(state.user.name)}"><span class="avatar">${esc(initials(state.user.name))}</span><span class="user-summary"><strong>${esc(state.user.name)}</strong><span>${isAdmin() ? 'Station operator' : 'Electric explorer'}</span></span></button>`
    : `<button class="text-button" data-action="login">Sign in</button><button class="button small" data-action="register">Create account ${icon('arrow')}</button>`;
  $('#sidebar-account').dataset.action = state.user ? 'logout' : 'login';
  $('#sidebar-account').setAttribute('aria-label', state.user ? 'Sign out' : 'Sign in');
  $('#sidebar-account').innerHTML = `${icon(state.user ? 'logout' : 'user')}<span>${state.user ? 'Sign out' : 'Sign in'}</span>`;
  $('#breadcrumb-page').textContent = links.find(([view]) => view === state.view)?.[2] || 'Discover stations';
}

function heading(eyebrow, title, description, action = '') {
  return `<div class="page-heading"><div><div class="eyebrow">${esc(eyebrow)}</div><h1>${esc(title)}</h1><p>${esc(description)}</p></div>${action || `<span class="date-label">${icon('clock')}${esc(new Date().toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' }))}</span>`}</div>`;
}

function render() {
  renderNavigation();
  if (state.view === 'bookings') renderBookings();
  else if (state.view === 'account') renderAccount();
  else if (state.view === 'stations') renderStationManagement();
  else if (state.view === 'customers') renderCustomers();
  else renderDiscover();
  document.title = `${$('#breadcrumb-page').textContent} · Voltway`;
}

function renderDiscover() {
  const name = state.user?.name?.trim().split(/\s+/)[0];
  const availableCount = state.stations.reduce((total, station) => total + available(station), 0);
  const activeCount = state.bookings.filter(booking => booking.bookingStatus === 'Confirmed').length;
  $('#page-content').innerHTML = `${heading('A BETTER WAY TO CHARGE', name ? `Good to see you, ${name}.` : 'Your next journey starts here.', 'A charging space for wherever life takes you.')}
    <section class="journey-banner" aria-label="Find and reserve charging capacity"><div class="journey-copy"><div class="eyebrow">LESS SEARCHING. MORE GOING.</div><h2>Your next charge,<br><em>within reach.</em></h2><p>Find a station, reserve a space, and take the next turn with confidence.</p><button class="button lime" data-action="focus-search">Find your station ${icon('arrow')}</button></div><div class="journey-art" aria-hidden="true"><div class="art-orbit"></div><div class="art-label">THE ROAD AHEAD IS ELECTRIC</div><div class="art-road"></div><div class="art-car"></div><div class="art-charger">${icon('bolt')}</div><div class="art-leaf"></div><div class="art-caption">FIND. RESERVE. GO.</div></div></section>
    <section class="metrics" aria-label="Network overview"><div class="metric"><div class="metric-icon">${icon('pin')}</div><div><div class="metric-number">${state.errors.stations ? '—' : state.stations.length.toLocaleString()}</div><div class="metric-label">Stations in the network</div></div></div><div class="metric"><div class="metric-icon">${icon('bolt')}</div><div><div class="metric-number">${state.errors.stations ? '—' : availableCount.toLocaleString()}</div><div class="metric-label">Spaces available now</div></div></div><div class="metric"><div class="metric-icon">${icon('ticket')}</div><div><div class="metric-number">${state.user && !state.errors.bookings ? activeCount.toLocaleString() : '—'}</div><div class="metric-label">${isAdmin() ? 'Active network reservations' : 'Your active reservations'}</div></div></div></section>
    <section aria-labelledby="stations-heading"><div class="section-heading"><div><h2 id="stations-heading">Find your next stop</h2><p>Available capacity, straight from the station network.</p></div><button class="refresh-button" data-action="refresh">${icon('refresh')}Refresh</button></div><div class="search-panel"><label class="search-box">${icon('search')}<span class="sr-only">Search stations by name or location</span><input id="station-search" type="search" placeholder="Search by station name or location" value="${esc(state.query)}" maxlength="150" autocomplete="off"></label><span class="search-divider"></span><label class="check-label"><input id="available-only" type="checkbox" ${state.availableOnly ? 'checked' : ''}>Available now</label><label class="sr-only" for="station-sort">Sort stations</label><select id="station-sort" class="sort-select"><option value="name" ${state.sort === 'name' ? 'selected' : ''}>Station name</option><option value="availability" ${state.sort === 'availability' ? 'selected' : ''}>Most available</option><option value="location" ${state.sort === 'location' ? 'selected' : ''}>Location</option></select></div><div id="station-results" class="stations-grid" aria-live="polite"></div><p class="capacity-note">${icon('shield')}Reservations hold current capacity. No online payment is collected.</p></section>`;
  renderStationResults();
}

function renderStationResults() {
  const target = $('#station-results');
  if (!target) return;
  if (state.errors.stations) { target.innerHTML = errorPanel(state.errors.stations); return; }
  const query = state.query.trim().toLocaleLowerCase();
  const stations = state.stations.filter(station => (!state.availableOnly || available(station) > 0) && `${station.stationName} ${station.location}`.toLocaleLowerCase().includes(query));
  stations.sort((a, b) => state.sort === 'availability' ? available(b) - available(a) : String(state.sort === 'location' ? a.location : a.stationName).localeCompare(String(state.sort === 'location' ? b.location : b.stationName)));
  if (!stations.length) {
    target.innerHTML = state.stations.length
      ? emptyState('No stations match this search', 'Try another station name or location, or include stations that are currently full.', '<button class="button secondary small" data-action="clear-search">Clear filters</button>')
      : emptyState('The network is ready for its first station', isAdmin() ? 'Add your first station and drivers can start reserving available capacity.' : 'No stations have been published yet. Check back after the station operator adds one.', isAdmin() ? '<button class="button small" data-action="new-station">Add a station</button>' : '<button class="button secondary small" data-action="refresh">Refresh stations</button>');
    return;
  }
  target.innerHTML = stations.map(station => `<article class="station-card"><div class="station-visual" aria-hidden="true"><span class="station-number">STATION ${String(station.id).padStart(3, '0')}</span><span class="status-tag ${available(station) > 0 ? '' : 'unavailable'}">${available(station) > 0 ? 'Available now' : 'Currently full'}</span><span class="charger-mini">${icon('bolt')}</span></div><div class="station-body"><h3>${esc(station.stationName)}</h3><div class="station-location">${icon('pin')}<span>${esc(station.location)}</span></div><div class="station-capacity"><span>Charging spaces</span><strong>${available(station)} of ${station.totalSlots} available</strong></div><progress class="capacity-track" value="${available(station)}" max="${station.totalSlots}" aria-label="${available(station)} of ${station.totalSlots} spaces available"></progress><div class="station-card-actions"><button class="text-button" data-action="station-details" data-id="${station.id}">View details</button>${available(station) > 0 ? `<button class="button" data-action="reserve" data-id="${station.id}">Reserve a space ${icon('arrow')}</button>` : `<button class="button ghost" data-action="station-details" data-id="${station.id}">View availability</button>`}</div></div></article>`).join('');
}

function renderBookings() {
  const active = state.bookings.filter(booking => booking.bookingStatus === 'Confirmed').length;
  $('#page-content').innerHTML = `${heading(isAdmin() ? 'NETWORK OPERATIONS' : 'YOUR JOURNEY, ORGANIZED', isAdmin() ? 'Every reservation. One place.' : 'My bookings', isAdmin() ? 'Manage current reservations and close completed charging sessions.' : 'Your next stop and every charge along the way.', '<button class="button secondary small" data-action="refresh">' + icon('refresh') + 'Refresh</button>')}
    <div class="section-heading"><p class="status-summary">${active} active reservation${active === 1 ? '' : 's'}</p>${!isAdmin() ? '<a class="text-button" href="#discover">Find another station →</a>' : ''}</div><div class="tabs" role="group" aria-label="Filter bookings">${['All', 'Confirmed', 'Completed', 'Cancelled'].map(filter => `<button class="tab" data-action="booking-filter" data-filter="${filter}" aria-pressed="${state.bookingFilter === filter}">${filter}${filter === 'All' ? ` (${state.bookings.length})` : ''}</button>`).join('')}</div><div id="bookings-results" class="bookings-list"></div>`;
  const target = $('#bookings-results');
  if (state.errors.bookings) { target.innerHTML = errorPanel(state.errors.bookings); return; }
  const bookings = [...state.bookings].sort((a, b) => b.bookingId - a.bookingId).filter(booking => state.bookingFilter === 'All' || booking.bookingStatus === state.bookingFilter);
  if (!bookings.length) {
    target.innerHTML = emptyState(state.bookings.length ? `No ${state.bookingFilter.toLowerCase()} bookings` : 'Your next stop is waiting', state.bookings.length ? 'Choose another status to see more of your reservation history.' : 'Explore the station network and reserve an available charging space.', '<a class="button small" href="#discover">Explore stations ' + icon('arrow') + '</a>', 'ticket');
    return;
  }
  target.innerHTML = bookings.map(booking => `<article class="booking-card"><div><div class="booking-meta"><span>RESERVATION #${esc(booking.bookingId)}</span>${statusBadge(booking.bookingStatus)}</div><h3>${esc(booking.stationName || stationById(booking.stationId)?.stationName || `Station #${booking.stationId}`)}</h3><div class="station-location">${icon('pin')}<span>${esc(booking.location || stationById(booking.stationId)?.location || 'Location unavailable')}</span></div><div class="booking-details"><span>Reserved <strong>${esc(formatDate(booking.createdAt))}</strong></span><span>Spaces <strong>1</strong></span>${isAdmin() ? `<span>Customer <strong>#${esc(booking.userId)}</strong></span>` : ''}<span><strong>No online payment</strong></span></div></div><div class="booking-actions"><button class="button secondary" data-action="booking-details" data-id="${booking.bookingId}">View details</button>${booking.bookingStatus === 'Confirmed' ? `<button class="button danger" data-action="cancel-booking" data-id="${booking.bookingId}">Cancel booking</button>${isAdmin() ? `<button class="button" data-action="complete-booking" data-id="${booking.bookingId}">${icon('check')}Mark complete</button>` : ''}` : ''}</div></article>`).join('');
}

function passwordField(name, label, autocomplete, hint = '', minimum = 12) {
  const id = name.replace(/([A-Z])/g, '-$1').toLowerCase();
  return `<label class="field" for="${id}"><span>${esc(label)}</span><span class="password-wrap"><input id="${id}" name="${name}" type="password" autocomplete="${autocomplete}" required minlength="${minimum}" maxlength="128" ${autocomplete === 'new-password' ? 'data-new-password="true"' : ''} ${hint ? `aria-describedby="${id}-hint"` : ''}><button type="button" class="password-toggle" data-action="toggle-password" data-target="${id}" aria-label="Show ${esc(label.toLowerCase())}">Show</button></span>${hint ? `<small id="${id}-hint">${esc(hint)}</small>` : ''}</label>`;
}

function validateNewPassword(field) {
  field.setCustomValidity(new TextEncoder().encode(field.value).length > 72 ? 'Use no more than 72 UTF-8 bytes. For English letters, numbers and symbols, this is 72 characters; some other characters use more bytes.' : '');
}

function renderAccount() {
  $('#page-content').innerHTML = `${heading('YOUR PERSONAL SPACE', 'Account settings', 'Keep your details up to date and your account secure.', '<button class="button secondary small" data-action="logout">' + icon('logout') + 'Sign out</button>')}<div class="profile-grid"><section class="panel"><div class="panel-heading">${icon('user')}<div><h2>Personal details</h2><p>The details connected to your reservations.</p></div></div><div class="account-summary"><span class="avatar">${esc(initials(state.user.name))}</span><div><strong>${esc(state.user.name)}</strong><p>${isAdmin() ? 'Station operator' : 'Voltway member'} · Account #${state.user.id}</p></div></div><form id="profile-form"><label class="field"><span>Full name</span><input name="name" value="${esc(state.user.name)}" autocomplete="name" required maxlength="100"></label><label class="field"><span>Email address</span><input name="email" type="email" value="${esc(state.user.email)}" autocomplete="email" required maxlength="254"><small>You will use this email address the next time you sign in.</small></label><div class="inline-error" role="alert"></div><div class="form-actions"><button class="button" type="submit">Save changes ${icon('check')}</button></div></form></section><section class="panel"><div class="panel-heading">${icon('shield')}<div><h2>Change password</h2><p>A fresh password keeps your account protected.</p></div></div><form id="password-form">${passwordField('currentPassword', 'Current password', 'current-password', '', 1)}${passwordField('newPassword', 'New password', 'new-password', 'Use at least 12 characters and no more than 72 UTF-8 bytes. English characters use one byte each.')}<div class="inline-error" role="alert"></div><div class="form-actions"><button class="button secondary" type="submit">Update password</button></div><p class="form-footnote">You will be signed out after changing your password.</p></form></section></div>`;
}

function renderStationManagement() {
  $('#page-content').innerHTML = `${heading('KEEP THE NETWORK MOVING', 'Station management', 'Publish stations and keep capacity information accurate.', '<button class="button" data-action="new-station">' + icon('plus') + 'Add station</button>')}${state.errors.stations ? errorPanel(state.errors.stations) : state.stations.length ? `<div class="table-panel"><table><thead><tr><th>STATION</th><th>LOCATION</th><th>CAPACITY</th><th>AVAILABLE</th><th>ACTIONS</th></tr></thead><tbody>${state.stations.map(station => `<tr><td><strong>${esc(station.stationName)}</strong><span class="table-subtitle">Station #${station.id}</span></td><td class="word-break">${esc(station.location)}</td><td>${station.totalSlots}</td><td><span class="status-tag ${available(station) ? '' : 'unavailable'}">${available(station)} spaces</span></td><td><div class="table-actions"><button class="button secondary" data-action="edit-station" data-id="${station.id}">${icon('edit')}Edit</button><button class="button danger" data-action="delete-station" data-id="${station.id}">Delete</button></div></td></tr>`).join('')}</tbody></table></div>` : emptyState('Build your charging network', 'Add a station with its name, location and available capacity. It will appear in the driver’s station explorer immediately.', '<button class="button" data-action="new-station">' + icon('plus') + 'Add your first station</button>')}<p class="admin-note">${icon('shield')}Available capacity follows confirmed bookings. Capacity changes cannot remove occupied spaces; stations with booking history are retained.</p>`;
}

function renderCustomers() {
  $('#page-content').innerHTML = `${heading('THE PEOPLE ON YOUR NETWORK', 'Customer accounts', 'Account information for reservation support.', '<button class="button secondary small" data-action="refresh-users">' + icon('refresh') + 'Refresh</button>')}${state.errors.users ? errorPanel(state.errors.users, 'refresh-users') : !state.usersLoaded ? '<div class="loading-inline"><span class="spinner"></span>Loading customer accounts…</div>' : state.users.length ? `<div class="table-panel"><table><thead><tr><th>CUSTOMER</th><th>EMAIL ADDRESS</th><th>ROLE</th><th>ACCOUNT ID</th></tr></thead><tbody>${state.users.map(user => `<tr><td><div class="member-name"><span class="avatar">${esc(initials(user.name))}</span><strong>${esc(user.name)}</strong></div></td><td class="word-break">${esc(user.email)}</td><td><span class="member-role">${user.role === 'ADMIN' ? 'OPERATOR' : 'CUSTOMER'}</span></td><td>#${user.id}</td></tr>`).join('')}</tbody></table></div>` : emptyState('No customer accounts yet', 'Newly registered members appear here.', '', 'users')}<p class="admin-note">${icon('shield')}Customer account information is visible to authorized operators. Account roles are controlled by server configuration.</p>`;
}

function stationById(id) { return state.stations.find(station => Number(station.id) === Number(id)); }
function bookingById(id) { return state.bookings.find(booking => Number(booking.bookingId) === Number(id)); }

async function refreshData({ quiet = false } = {}) {
  const generation = ++dataGeneration;
  if (!quiet) state.loading = true;
  const keys = ['stations', ...(state.user ? ['bookings', 'notifications'] : [])];
  const results = await Promise.allSettled(keys.map(key => request(`/api/${key}`)));
  if (generation !== dataGeneration) return;
  results.forEach((result, index) => {
    const key = keys[index];
    if (result.status === 'fulfilled') { state[key] = result.value; delete state.errors[key]; }
    else state.errors[key] = result.reason.message;
  });
  state.loading = false;
  render();
  if (dialog.open && dialog.dataset.kind === 'inbox') renderInbox();
  return results.every(result => result.status === 'fulfilled');
}

async function loadUsers() {
  if (!isAdmin()) return;
  const operationSession = sessionGeneration;
  try {
    const users = await request('/api/admin/users');
    if (operationSession !== sessionGeneration) return;
    state.users = users; delete state.errors.users; state.usersLoaded = true;
  } catch (error) { if (operationSession !== sessionGeneration) return; state.errors.users = error.message; }
  if (state.view === 'customers') renderCustomers();
}

function navigate() {
  let next = location.hash.replace('#', '') || 'discover';
  if (!['discover', 'bookings', 'account', 'stations', 'customers'].includes(next)) next = 'discover';
  if (!state.user && next !== 'discover') { history.replaceState(null, '', '#discover'); next = 'discover'; }
  if (!isAdmin() && ['stations', 'customers'].includes(next)) { history.replaceState(null, '', '#discover'); next = 'discover'; }
  if (next !== state.view && hasUnsavedAccountChanges() && !window.confirm('You have unsaved account changes. Leave without saving?')) { history.replaceState(null, '', `#${state.view}`); return; }
  state.view = next;
  render();
  if (next === 'customers' && !state.usersLoaded) loadUsers();
}

function openDialog(html, { wide = false, kind = '' } = {}) {
  $('#dialog-content').innerHTML = `<div class="dialog-inner"><button class="icon-button dialog-close" data-action="close-dialog" aria-label="Close dialog">${icon('close')}</button>${html}</div>`;
  dialog.classList.toggle('wide', wide);
  dialog.dataset.kind = kind;
  renderDialogDemoNotice();
  if (!dialog.open) dialog.showModal();
  requestAnimationFrame(() => {
    const focusTarget = $('input, button[type="submit"]', dialog) || $('.dialog-close', dialog);
    focusTarget?.focus();
  });
}

function showAuth(mode = 'login', returnStationId = null) {
  const register = mode === 'register';
  openDialog(`<div class="auth-logo"><span class="brand-mark">${icon('bolt')}</span>voltway.</div><div class="dialog-eyebrow">${register ? 'A BETTER WAY TO CHARGE' : 'WELCOME BACK'}</div><h2 id="dialog-title">${register ? 'The road ahead starts here.' : 'Ready for your next stop?'}</h2><p class="dialog-intro">${register ? 'Create your account and reserve your first charging space.' : 'Sign in to manage your reservations and find your next charge.'}</p><form id="auth-form" data-mode="${mode}" ${returnStationId ? `data-station="${returnStationId}"` : ''}>${register ? '<label class="field"><span>Full name</span><input name="name" autocomplete="name" required maxlength="100" placeholder="Your full name"></label>' : ''}<label class="field"><span>Email address</span><input name="email" type="email" autocomplete="email" required maxlength="254" placeholder="you@example.com"></label>${passwordField('password', 'Password', register ? 'new-password' : 'current-password', register ? 'Use at least 12 characters and no more than 72 UTF-8 bytes. English characters use one byte each.' : '', register ? 12 : 1)}<div class="inline-error" role="alert"></div><button class="button full" type="submit">${register ? 'Create account' : 'Sign in'} ${icon('arrow')}</button></form><p class="auth-toggle">${register ? 'Already part of the journey?' : 'New to Voltway?'} <button class="text-button" data-action="${register ? 'login' : 'register'}" ${returnStationId ? `data-station="${returnStationId}"` : ''}>${register ? 'Sign in' : 'Create an account'}</button></p><p class="form-footnote">We use essential session cookies to keep your account signed in. <button class="text-button" data-action="privacy">Privacy information</button></p>`, { kind: 'auth' });
}

function showStationDetails(id) {
  const station = stationById(id);
  if (!station) return;
  openDialog(`<div class="dialog-eyebrow">STATION ${String(station.id).padStart(3, '0')}</div><h2 id="dialog-title">${esc(station.stationName)}</h2><p class="dialog-intro">${icon('pin')} ${esc(station.location)}</p><dl class="detail-list"><div><dt>Total capacity</dt><dd>${station.totalSlots} spaces</dd></div><div><dt>Available right now</dt><dd>${available(station)} spaces</dd></div><div><dt>Reservation type</dt><dd>Current capacity</dd></div><div><dt>Online payment</dt><dd>None collected</dd></div></dl><p class="info-note">A reservation holds one space until you cancel or the station operator completes your session. Confirm charger compatibility with the operator before travelling.</p>${available(station) > 0 ? `<button class="button full" data-action="reserve" data-id="${station.id}">Reserve a space ${icon('arrow')}</button>` : '<p class="confirm-copy">This station is currently full. Refresh the network to check whether a space has become available.</p><button class="button secondary full" data-action="close-dialog">Back to stations</button>'}`, { kind: 'station' });
}

function showReserve(id) {
  const station = stationById(id);
  if (!station) return;
  if (!state.user) { showAuth('login', id); return; }
  bookingRequest = { stationId: Number(id), key: crypto.randomUUID() };
  openDialog(`<div class="dialog-eyebrow">YOUR NEXT STOP</div><h2 id="dialog-title">Make room for your journey.</h2><p class="dialog-intro">Review your reservation before you confirm.</p><div class="reservation-station">${icon('bolt')}<div><h3>${esc(station.stationName)}</h3><p>${esc(station.location)}</p></div></div><dl class="detail-list"><div><dt>Reserved for</dt><dd>${esc(state.user.name)}</dd></div><div><dt>Charging spaces</dt><dd>1 space · Current availability</dd></div><div><dt>Reservation fee</dt><dd>₹0 · No online charge</dd></div><div><dt>Duration</dt><dd>Until cancelled or completed</dd></div></dl><p class="info-note">This reservation holds current station capacity. It does not select a future time slot or activate charging equipment. No payment details are needed.</p><form id="reserve-form"><div class="inline-error" role="alert"></div><button class="button full" type="submit">Confirm reservation ${icon('arrow')}</button></form>`, { kind: 'reserve' });
}

function showBookingDetails(id) {
  const booking = bookingById(id);
  if (!booking) return;
  openDialog(`<div class="dialog-eyebrow">RESERVATION #${esc(booking.bookingId)}</div><h2 id="dialog-title">Your booking details</h2><p class="dialog-intro">${statusBadge(booking.bookingStatus)}</p><div class="reservation-station">${icon('bolt')}<div><h3>${esc(booking.stationName || stationById(booking.stationId)?.stationName || `Station #${booking.stationId}`)}</h3><p>${esc(booking.location || stationById(booking.stationId)?.location)}</p></div></div><dl class="detail-list"><div><dt>Created</dt><dd>${esc(formatDate(booking.createdAt))}</dd></div><div><dt>Charging spaces</dt><dd>1</dd></div><div><dt>Online payment</dt><dd>None collected</dd></div>${isAdmin() ? `<div><dt>Customer account</dt><dd>#${booking.userId}</dd></div>` : ''}</dl><p class="info-note">${booking.bookingStatus === 'Confirmed' ? 'Your space is reserved. Cancel if your plans change so another driver can use it. The operator marks the reservation complete when the session finishes.' : booking.bookingStatus === 'Cancelled' ? 'This reservation was cancelled. Its space has been returned to the station’s available capacity.' : 'The operator marked this reservation complete. It is retained in your booking history.'}</p><button class="button secondary full" data-action="close-dialog">Close details</button>`, { kind: 'booking' });
}

function showBookingAction(id, action) {
  const booking = bookingById(id);
  if (!booking) return;
  const complete = action === 'complete';
  openDialog(`<div class="dialog-eyebrow">RESERVATION #${esc(id)}</div><h2 id="dialog-title">${complete ? 'Complete this reservation?' : 'Plans changed?'}</h2><p class="confirm-copy">${complete ? 'Confirm that the charging session at' : 'Cancel your reservation at'} <strong>${esc(booking.stationName || stationById(booking.stationId)?.stationName || `Station #${booking.stationId}`)}</strong>${complete ? ' has finished. The reserved space becomes available again.' : '. The space will become available for another driver. This action cannot be undone.'}</p><form id="booking-action-form" data-id="${id}" data-operation="${action}"><div class="inline-error" role="alert"></div><div class="form-actions"><button class="button secondary" type="button" data-action="close-dialog">Keep reservation</button><button class="button ${complete ? '' : 'danger'}" type="submit">${complete ? 'Mark complete' : 'Cancel booking'}</button></div></form>`, { kind: 'booking-action' });
}

function showStationForm(id) {
  const station = id ? stationById(id) : null;
  openDialog(`<div class="dialog-eyebrow">STATION OPERATIONS</div><h2 id="dialog-title">${station ? 'Keep your station up to date.' : 'Make the next charge possible.'}</h2><p class="dialog-intro">${station ? 'Update the details drivers see in the network.' : 'Add a station for drivers to discover and reserve.'}</p><form id="station-form" ${station ? `data-id="${station.id}"` : ''}><label class="field"><span>Station name</span><input name="stationName" required maxlength="150" value="${esc(station?.stationName || '')}" placeholder="e.g. Riverside Charging Hub"></label><label class="field"><span>Location</span><input name="location" required maxlength="255" value="${esc(station?.location || '')}" placeholder="Street, neighborhood and city"></label><label class="field"><span>Total charging spaces</span><input name="totalSlots" type="number" min="1" max="10000" step="1" required value="${station?.totalSlots || 1}"><small>${station ? 'Available capacity is adjusted automatically to preserve occupied spaces.' : 'All spaces at a new station will initially be available.'}</small></label><div class="inline-error" role="alert"></div><button class="button full" type="submit">${station ? 'Save station changes' : 'Add station'} ${icon('check')}</button></form>`, { kind: 'station-form' });
}

function showDeleteStation(id) {
  const station = stationById(id);
  if (!station) return;
  openDialog(`<div class="dialog-eyebrow">STATION OPERATIONS</div><h2 id="dialog-title">Delete this station?</h2><p class="confirm-copy"><strong>${esc(station.stationName)}</strong> will be removed from the network. Stations with booking history are protected and cannot be deleted.</p><form id="delete-station-form" data-id="${id}"><div class="inline-error" role="alert"></div><div class="form-actions"><button class="button secondary" type="button" data-action="close-dialog">Keep station</button><button class="button danger" type="submit">Delete station</button></div></form>`, { kind: 'station-delete' });
}

function renderInbox() {
  const unread = state.notifications.filter(item => !item.read).length;
  const list = [...state.notifications].sort((a, b) => b.id - a.id);
  openDialog(`<div class="dialog-eyebrow">THE LATEST ON YOUR JOURNEY</div><h2 id="dialog-title">Your notifications</h2><p class="dialog-intro">${unread} unread notification${unread === 1 ? '' : 's'}. Booking updates stay right here.</p><div class="notification-list">${state.errors.notifications ? errorPanel(state.errors.notifications, 'refresh-inbox') : list.length ? list.map(item => `<article class="notification-item ${item.read ? 'read' : ''}"><span class="notification-marker" aria-hidden="true"></span><div><p>${esc(item.message)}</p><time datetime="${esc(item.createdAt)}">${esc(formatDate(item.createdAt))}</time></div>${!item.read ? `<button class="text-button" data-action="read-notification" data-id="${item.id}">Mark read</button>` : '<span class="sr-only">Read</span>'}</article>`).join('') : emptyState('All quiet for now', 'Your reservation updates will appear here after you make a booking.', '', 'bell')}</div>`, { wide: true, kind: 'inbox' });
}

function showContent(key) {
  const item = siteContent(key, state.siteInfo);
  if (!item) return;
  openDialog(`<div class="dialog-eyebrow">${esc(item.eyebrow)}</div><h2 id="dialog-title">${esc(item.title)}</h2><div class="content-sections">${item.sections.map(section => `<section><h3>${esc(section.title)}</h3><p>${esc(section.body)}</p></section>`).join('')}</div>`, { wide: true, kind: 'information' });
}

function setBusy(form, busy) {
  form.setAttribute('aria-busy', String(busy));
  for (const element of form.elements) element.disabled = busy;
  const submit = $('[type="submit"]', form);
  if (submit) {
    if (busy) { submit.dataset.original = submit.innerHTML; submit.textContent = 'Please wait…'; }
    else if (submit.dataset.original) submit.innerHTML = submit.dataset.original;
  }
}

function hasUnsavedAccountChanges() {
  return Boolean($('#profile-form[data-dirty="true"], #password-form[data-dirty="true"]'));
}

function canCloseDialog() {
  return !$('#station-form[data-dirty="true"]', dialog) || window.confirm('You have unsaved station changes. Leave without saving?');
}

function showFormError(form, error) {
  const fieldErrors = error.details?.fieldErrors || error.details?.validationErrors;
  let message = error.message;
  if (fieldErrors && typeof fieldErrors === 'object') {
    const messages = Object.entries(fieldErrors).map(([field, detail]) => `${field.replace(/([A-Z])/g, ' $1')}: ${Array.isArray(detail) ? detail.join(', ') : detail}`);
    if (messages.length) message += ` ${messages.join('. ')}`;
  }
  const target = $('.inline-error', form);
  if (target) { target.textContent = message; target.tabIndex = -1; target.focus(); }
  else toast(message, true);
}

async function submitForm(event) {
  const form = event.target;
  if (!(form instanceof HTMLFormElement) || !['auth-form', 'profile-form', 'password-form', 'reserve-form', 'booking-action-form', 'station-form', 'delete-station-form'].includes(form.id)) return;
  event.preventDefault();
  for (const field of form.querySelectorAll('[data-new-password]')) validateNewPassword(field);
  if (form.getAttribute('aria-busy') === 'true' || !form.reportValidity()) return;
  const data = Object.fromEntries(new FormData(form));
  const operationSession = sessionGeneration;
  const targetError = $('.inline-error', form);
  if (targetError) targetError.textContent = '';
  setBusy(form, true);
  try {
    if (form.id === 'auth-form') {
      const register = form.dataset.mode === 'register';
      let user;
      if (register) {
        await request('/api/auth/register', { method: 'POST', body: { name: data.name.trim(), email: data.email.trim(), password: data.password }, silent: true });
        resetCsrf();
      }
      user = await request('/api/auth/login', { method: 'POST', body: { email: data.email.trim(), password: data.password }, silent: true });
      resetCsrf(); ++sessionGeneration; state.user = user; sessionExpiryHandled = false; state.usersLoaded = false; state.errors = {};
      const returnId = form.dataset.station;
      dialog.close();
      toast(register ? 'Your account is ready. Welcome to Voltway.' : `Welcome back, ${user.name.trim().split(/\s+/)[0]}.`);
      await refreshData();
      if (returnId) showReserve(returnId);
    } else if (form.id === 'profile-form') {
      const updatedUser = await request('/api/profile', { method: 'PUT', body: { name: data.name.trim(), email: data.email.trim() } });
      if (operationSession !== sessionGeneration) return;
      state.user = updatedUser;
      render(); toast('Your account details have been updated.');
    } else if (form.id === 'password-form') {
      await request('/api/auth/password', { method: 'PUT', body: data });
      if (operationSession !== sessionGeneration) return;
      clearSession(); resetCsrf(); toast('Your password has been changed. Sign in with your new password.');
      showAuth();
    } else if (form.id === 'reserve-form') {
      const currentRequest = bookingRequest;
      const booking = await request('/api/bookings', { method: 'POST', body: { stationId: currentRequest.stationId }, headers: { 'Idempotency-Key': currentRequest.key } });
      if (operationSession !== sessionGeneration) return;
      bookingRequest = undefined;
      await refreshData({ quiet: true });
      if (operationSession !== sessionGeneration) return;
      const savedBooking = !state.errors.bookings ? bookingById(booking.bookingId) || booking : booking;
      const station = stationById(savedBooking.stationId);
      const feedback = reservationFeedback(savedBooking.bookingStatus);
      openDialog(`<div class="dialog-success-icon">${icon(feedback.active ? 'check' : 'ticket')}</div><div class="dialog-eyebrow">${esc(feedback.eyebrow)}</div><h2 id="dialog-title">${esc(feedback.title)}</h2><p class="dialog-intro">${esc(feedback.description)}</p><div class="reservation-station">${icon('bolt')}<div><h3>${esc(savedBooking.stationName || station?.stationName)}</h3><p>${esc(savedBooking.location || station?.location)}</p></div></div><dl class="detail-list"><div><dt>Reservation number</dt><dd>#${savedBooking.bookingId}</dd></div><div><dt>Status</dt><dd>${esc(feedback.status)}</dd></div><div><dt>Online payment</dt><dd>None collected</dd></div></dl><button class="button full" data-action="go-bookings">${isAdmin() ? 'View all bookings' : 'View my bookings'} ${icon('arrow')}</button>`, { kind: 'reservation-success' });
    } else if (form.id === 'booking-action-form') {
      await request(`/api/bookings/${form.dataset.id}/${form.dataset.operation}`, { method: 'POST' });
      if (operationSession !== sessionGeneration) return;
      dialog.close(); toast(form.dataset.operation === 'complete' ? 'Reservation completed. The space is available again.' : 'Reservation cancelled. The space is available again.');
      await refreshData({ quiet: true });
    } else if (form.id === 'station-form') {
      const body = { stationName: data.stationName.trim(), location: data.location.trim(), totalSlots: Number(data.totalSlots) };
      if (!form.dataset.id) body.avaialableSlots = body.totalSlots;
      await request(`/api/stations${form.dataset.id ? `/${form.dataset.id}` : ''}`, { method: form.dataset.id ? 'PUT' : 'POST', body });
      if (operationSession !== sessionGeneration) return;
      dialog.close(); toast(form.dataset.id ? 'Station details updated.' : 'Your station is now in the network.');
      await refreshData({ quiet: true });
    } else if (form.id === 'delete-station-form') {
      await request(`/api/stations/${form.dataset.id}`, { method: 'DELETE' });
      if (operationSession !== sessionGeneration) return;
      dialog.close(); toast('The station has been removed.'); await refreshData({ quiet: true });
    }
  } catch (error) {
    if (form.isConnected) showFormError(form, error);
    else toast(error.message, true);
    if (form.id === 'reserve-form' && error.status === 409) {
      const stations = await request('/api/stations').catch(() => null);
      if (stations) { state.stations = stations; render(); }
    }
  } finally { if (form.isConnected) setBusy(form, false); }
}

function clearSession() {
  ++dataGeneration;
  ++sessionGeneration;
  state.user = null; state.bookings = []; state.notifications = []; state.users = []; state.usersLoaded = false;
  delete state.errors.bookings; delete state.errors.notifications; delete state.errors.users;
  bookingRequest = undefined;
  if (dialog.open) dialog.close();
  state.view = 'discover'; history.replaceState(null, '', '#discover'); render();
}

async function doAction(button) {
  const { action, id } = button.dataset;
  if (['login', 'register', 'reserve', 'help', 'privacy', 'terms'].includes(action)) {
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    await siteInfoReady;
    button.disabled = false;
    button.removeAttribute('aria-busy');
  }
  if (action === 'close-dialog') { if (canCloseDialog()) dialog.close(); }
  else if (action === 'login' || action === 'register') showAuth(action, button.dataset.station);
  else if (['help', 'privacy', 'terms'].includes(action)) showContent(action);
  else if (action === 'account') location.hash = 'account';
  else if (action === 'focus-search') { $('#station-search')?.focus(); $('#stations-heading')?.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'center' }); }
  else if (action === 'station-details') showStationDetails(id);
  else if (action === 'reserve') showReserve(id);
  else if (action === 'booking-details') showBookingDetails(id);
  else if (action === 'cancel-booking') showBookingAction(id, 'cancel');
  else if (action === 'complete-booking') showBookingAction(id, 'complete');
  else if (action === 'new-station') showStationForm();
  else if (action === 'edit-station') showStationForm(id);
  else if (action === 'delete-station') showDeleteStation(id);
  else if (action === 'go-bookings') { dialog.close(); location.hash = 'bookings'; if (state.view === 'bookings') renderBookings(); }
  else if (action === 'booking-filter') { state.bookingFilter = button.dataset.filter; renderBookings(); }
  else if (action === 'clear-search') { state.query = ''; state.availableOnly = false; renderDiscover(); $('#station-search')?.focus(); }
  else if (action === 'toggle-password') {
    const input = document.getElementById(button.dataset.target);
    const visible = input.type === 'password'; input.type = visible ? 'text' : 'password';
    button.textContent = visible ? 'Hide' : 'Show'; button.setAttribute('aria-label', `${visible ? 'Hide' : 'Show'} password`);
  } else if (action === 'inbox') { renderInbox(); }
  else if (action === 'refresh-inbox') { await refreshData({ quiet: true }); if (state.user) renderInbox(); }
  else if (action === 'refresh-users') { button.disabled = true; await loadUsers(); }
  else if (action === 'refresh') {
    button.disabled = true;
    const success = await refreshData();
    if (success) toast('You’re up to date with the charging network.');
    if (button.isConnected) button.disabled = false;
  } else if (action === 'read-notification') {
    button.disabled = true;
    const operationSession = sessionGeneration;
    try {
      await request(`/api/notifications/${id}/read`, { method: 'PUT' });
      if (operationSession !== sessionGeneration) return;
      const notification = state.notifications.find(item => Number(item.id) === Number(id));
      if (notification) notification.read = true;
      renderNavigation(); renderInbox();
    } catch (error) { button.disabled = false; toast(error.message, true); }
  } else if (action === 'logout') {
    button.disabled = true;
    try { await request('/api/auth/logout', { method: 'POST' }); clearSession(); resetCsrf(); toast('You have been signed out. See you on the next journey.'); }
    catch (error) { button.disabled = false; toast(error.message, true); }
  }
}

document.addEventListener('click', event => {
  const button = event.target.closest('[data-action]');
  if (button && !button.disabled) doAction(button).catch(error => toast(error.message, true));
});
document.addEventListener('submit', submitForm);
document.addEventListener('input', event => {
  if (event.target.id === 'station-search') { state.query = event.target.value; renderStationResults(); }
  if (event.target.matches('[data-new-password]')) validateNewPassword(event.target);
  const form = event.target.closest('#profile-form, #password-form, #station-form');
  if (form) form.dataset.dirty = 'true';
});
document.addEventListener('change', event => {
  if (event.target.id === 'available-only') { state.availableOnly = event.target.checked; renderStationResults(); }
  if (event.target.id === 'station-sort') { state.sort = event.target.value; renderStationResults(); }
});
dialog.addEventListener('click', event => { if (event.target === dialog) { const box = dialog.getBoundingClientRect(); if ((event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) && canCloseDialog()) dialog.close(); } });
dialog.addEventListener('cancel', event => { if (!canCloseDialog()) event.preventDefault(); });
dialog.addEventListener('close', () => { if (!dialog.open) $('#dialog-content').replaceChildren(); });
window.addEventListener('beforeunload', event => { if (hasUnsavedAccountChanges() || $('#station-form[data-dirty="true"]', dialog)) { event.preventDefault(); event.returnValue = ''; } });
window.addEventListener('hashchange', navigate);
window.addEventListener('voltway:session-expired', () => {
  if (!state.user || sessionExpiryHandled) return;
  sessionExpiryHandled = true; clearSession(); resetCsrf(); toast('Your session has ended. Sign in again to continue.', true);
});
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });

async function start() {
  siteInfoReady = loadSiteInfo();
  try { state.user = await request('/api/auth/me', { silent: true }); }
  catch (error) { if (!(error instanceof ApiError) || error.status !== 401) toast(error.message, true); }
  navigate();
  $('#page-content').innerHTML = '<div class="initial-loading"><span class="spinner"></span><p>Connecting to your charging network…</p></div>';
  await refreshData();
}

start().catch(error => { $('#page-content').innerHTML = errorPanel(error.message); });
