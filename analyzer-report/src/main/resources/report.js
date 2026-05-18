/* ─────────────────────────────────────────────────────────────────────
   Codebase Analyzer — HTML report behaviour
   Loaded from classpath by HtmlReportGenerator.loadResource().
   Self-contained, no external dependencies.
   ───────────────────────────────────────────────────────────────────── */

// ─── Report mode selector (B7): dev | lead | exec ────────────────────
// Mode is held on <body data-mode="...">. CSS rules in report.css hide
// elements whose data-modes attribute doesn't include the active mode.
// The CLI flag --mode sets the initial value (server-side render); this
// dropdown lets the viewer switch on the fly.
(function initModeSwitcher() {
  var sel = document.getElementById('modeSelect');
  if (!sel) return;
  var initial = document.body.getAttribute('data-mode') || 'dev';
  sel.value = initial;
  sel.addEventListener('change', function () {
    document.body.setAttribute('data-mode', sel.value);
    // If the current page is hidden in the new mode, fall back to overview.
    var active = document.querySelector('.page.active');
    if (active) {
      var allowed = active.getAttribute('data-modes');
      if (allowed && allowed.split(/\s+/).indexOf(sel.value) === -1) {
        showPage('overview');
        if (history.replaceState) history.replaceState(null, '', '#overview');
      }
    }
  });
})();

// ─── Dashboard page routing ───────────────────────────────────────────
// Sidebar nav + URL hash → toggles the active <section class="page">.
// KPI cards on the Overview page can also include a data-prefilter-* attribute
// so clicking e.g. "Critical issues" jumps to Findings AND pre-applies the
// severity filter for an instant drill-down.
function showPage(name, prefilters) {
  if (!name) name = 'overview';
  document.querySelectorAll('.page').forEach(function (p) {
    p.classList.toggle('active', p.getAttribute('data-page') === name);
  });
  document.querySelectorAll('.nav-link').forEach(function (l) {
    l.classList.toggle('active', l.getAttribute('data-page') === name);
  });
  window.scrollTo({ top: 0, behavior: 'instant' });
  if (prefilters && typeof window.applyPrefilters === 'function') {
    window.applyPrefilters(prefilters);
  }
}

document.addEventListener('click', function (e) {
  var link = e.target.closest('a[data-page-link], a.nav-link');
  if (!link) return;
  e.preventDefault();
  var name = link.getAttribute('data-page') || link.getAttribute('data-page-link');
  var prefilters = {};
  if (link.getAttribute('data-prefilter-severity'))   prefilters.severity   = link.getAttribute('data-prefilter-severity');
  if (link.getAttribute('data-prefilter-category'))   prefilters.category   = link.getAttribute('data-prefilter-category');
  if (link.getAttribute('data-prefilter-confidence')) prefilters.confidence = link.getAttribute('data-prefilter-confidence');
  if (history.replaceState) history.replaceState(null, '', '#' + name);
  showPage(name, prefilters);
});

window.addEventListener('hashchange', function () {
  showPage((location.hash || '').replace(/^#/, ''));
});

// Initial page on load
(function initPage() {
  var initial = (location.hash || '').replace(/^#/, '') || 'overview';
  showPage(initial);
})();

// ─── Findings page: filters + pagination ─────────────────────────────
(function () {
  var activeSeverity = 'all';
  var activeCategory = 'all';
  var activeConfidence = 'trusted';   // default: hide LOW (speculative) findings
  var searchTerm = '';
  var issueTypeFilters = {};
  var currentPage = 1;
  var pageSize = 50;

  // Filter buttons
  document.querySelectorAll('.filter-btn').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var type = this.getAttribute('data-filter-type');
      var value = this.getAttribute('data-value');
      this.parentElement.querySelectorAll('.filter-btn').forEach(function (b) {
        b.classList.remove('active');
      });
      this.classList.add('active');
      if (type === 'severity')   activeSeverity   = value;
      if (type === 'category')   activeCategory   = value;
      if (type === 'confidence') activeConfidence = value;
      currentPage = 1;
      applyFilters();
    });
  });

  // Search input
  var searchInput = document.getElementById('searchInput');
  if (searchInput) {
    var searchTimeout;
    searchInput.addEventListener('input', function () {
      clearTimeout(searchTimeout);
      searchTimeout = setTimeout(function () {
        searchTerm = searchInput.value.toLowerCase().trim();
        currentPage = 1;
        applyFilters();
      }, 150);
    });
  }

  // Page-size selector
  var pageSizeSel = document.getElementById('pageSizeSelect');
  if (pageSizeSel) {
    pageSizeSel.addEventListener('change', function () {
      pageSize = parseInt(this.value, 10);
      currentPage = 1;
      applyFilters();
    });
  }

  function applyFilters() {
    var cards = document.querySelectorAll('.finding-card');
    var matchedCards = [];

    cards.forEach(function (card) {
      var matchSev = activeSeverity === 'all' || card.getAttribute('data-severity') === activeSeverity;
      var matchCat = activeCategory === 'all' || card.getAttribute('data-category') === activeCategory;

      var cardConf = card.getAttribute('data-confidence') || 'MEDIUM';
      var matchConf;
      if (activeConfidence === 'all') {
        matchConf = true;
      } else if (activeConfidence === 'trusted') {
        matchConf = (cardConf === 'CERTAIN' || cardConf === 'HIGH' || cardConf === 'MEDIUM');
      } else {
        matchConf = (cardConf === activeConfidence);
      }

      var matchSearch = !searchTerm || card.getAttribute('data-search').indexOf(searchTerm) !== -1;

      var catName = card.getAttribute('data-category');
      var issueType = issueTypeFilters[catName];
      var matchIssue = !issueType || issueType === 'all' || card.getAttribute('data-title') === issueType;

      if (matchSev && matchCat && matchConf && matchSearch && matchIssue) {
        matchedCards.push(card);
      }
    });

    // Pagination
    var totalMatched = matchedCards.length;
    var totalPages = pageSize > 0 ? Math.ceil(totalMatched / pageSize) : 1;
    if (currentPage > totalPages) currentPage = totalPages;
    if (currentPage < 1) currentPage = 1;

    // ── UX: if any per-category issue-type filter is active, jump to the first
    //   page that actually contains a matched card from that category. Without
    //   this, the user picks a filter, lands on page 1 which has 0 matches for
    //   that category, and thinks the section "disappeared".
    var activeIssueCats = Object.keys(issueTypeFilters).filter(function (k) {
      return issueTypeFilters[k] && issueTypeFilters[k] !== 'all';
    });
    if (pageSize > 0 && activeIssueCats.length > 0) {
      var firstIdx = -1;
      for (var j = 0; j < matchedCards.length; j++) {
        if (activeIssueCats.indexOf(matchedCards[j].getAttribute('data-category')) !== -1) {
          firstIdx = j; break;
        }
      }
      if (firstIdx >= 0) {
        var desiredPage = Math.floor(firstIdx / pageSize) + 1;
        if (desiredPage !== currentPage) currentPage = desiredPage;
      }
    }

    var startIdx = pageSize > 0 ? (currentPage - 1) * pageSize : 0;
    var endIdx   = pageSize > 0 ? startIdx + pageSize : totalMatched;

    cards.forEach(function (card) { card.classList.add('hidden'); });
    for (var i = startIdx; i < endIdx && i < totalMatched; i++) {
      matchedCards[i].classList.remove('hidden');
    }

    // Count matched cards per category (before pagination slicing)
    var matchedPerCategory = {};
    matchedCards.forEach(function (card) {
      var cat = card.getAttribute('data-category');
      matchedPerCategory[cat] = (matchedPerCategory[cat] || 0) + 1;
    });

    document.querySelectorAll('.category-section').forEach(function (section) {
      var catName = section.getAttribute('data-category');
      var totalForCat = matchedPerCategory[catName] || 0;
      var visibleOnPage = section.querySelectorAll('.finding-card:not(.hidden)').length;
      var countBadge = section.querySelector('.category-count');
      if (totalForCat === 0) {
        // Truly nothing matched the active filters in this category — hide it.
        section.classList.add('all-filtered');
      } else {
        // We have matches; never auto-collapse — that hid the section in a way
        // that looked like the filter broke. Just update the count badge.
        section.classList.remove('all-filtered');
        if (countBadge) {
          if (visibleOnPage > 0 && visibleOnPage === totalForCat) {
            countBadge.textContent = totalForCat + ' findings';
          } else if (visibleOnPage > 0) {
            countBadge.textContent = visibleOnPage + ' of ' + totalForCat + ' on this page';
          } else {
            countBadge.textContent = '0 of ' + totalForCat + ' on this page — change page';
          }
        }
      }
    });

    // Pagination controls
    var pageInfo       = document.getElementById('pageInfo');
    var prevBtn        = document.getElementById('prevPage');
    var nextBtn        = document.getElementById('nextPage');
    var paginationDiv  = document.getElementById('paginationControls');

    if (paginationDiv) {
      if (pageSize === 0 || totalPages <= 1) {
        paginationDiv.style.display = totalMatched > 0 && pageSize > 0 ? 'flex' : 'none';
        if (prevBtn) prevBtn.disabled = true;
        if (nextBtn) nextBtn.disabled = true;
        if (pageInfo) pageInfo.textContent = totalMatched + ' findings';
      } else {
        paginationDiv.style.display = 'flex';
        if (prevBtn) prevBtn.disabled = currentPage <= 1;
        if (nextBtn) nextBtn.disabled = currentPage >= totalPages;
        if (pageInfo) pageInfo.textContent = 'Page ' + currentPage + ' of ' + totalPages + ' (' + totalMatched + ' findings)';
      }
    }

    var noResults = document.getElementById('noResults');
    if (noResults) noResults.style.display = totalMatched === 0 ? 'block' : 'none';
  }

  // Expose globally
  window.applyFilters = applyFilters;

  // KPI-card shortcut: simulate a click on the matching filter button so the
  // active state, the filter variable, and the DOM all stay in sync.
  window.applyPrefilters = function (prefilters) {
    if (!prefilters) return;
    function clickFilter(type, value) {
      var sel = '.filter-btn[data-filter-type="' + type + '"][data-value="' + value + '"]';
      var btn = document.querySelector(sel);
      if (btn) btn.click();
    }
    if (prefilters.severity)   clickFilter('severity',   prefilters.severity);
    if (prefilters.category)   clickFilter('category',   prefilters.category);
    if (prefilters.confidence) clickFilter('confidence', prefilters.confidence);
  };

  window.issueTypeFilters = issueTypeFilters;
  window.changePage = function (delta) {
    currentPage += delta;
    applyFilters();
    var container = document.getElementById('findingsContainer');
    if (container) container.scrollIntoView({ behavior: 'smooth' });
  };

  applyFilters();  // initial render
})();

function toggleCategory(header) {
  header.parentElement.classList.toggle('collapsed');
}

// Migration Readiness CTA — navigate to Findings page + prefilter by category
function focusMigrationBlockers() {
  if (history.replaceState) history.replaceState(null, '', '#findings');
  showPage('findings', { category: 'MIGRATION_BLOCKER' });
}

function toggleLocations(header) {
  var table = header.nextElementSibling;
  var toggle = header.querySelector('.locations-toggle');
  if (table.style.display === 'none') {
    table.style.display = 'table';
    toggle.classList.add('open');
  } else {
    table.style.display = 'none';
    toggle.classList.remove('open');
  }
}

// Auto-fix diffs (B6) — same toggle pattern but flips a div, not a table.
function toggleFixes(header) {
  var content = header.nextElementSibling;
  var toggle = header.querySelector('.fixes-toggle');
  if (content.style.display === 'none') {
    content.style.display = 'block';
    if (toggle) toggle.classList.add('open');
  } else {
    content.style.display = 'none';
    if (toggle) toggle.classList.remove('open');
  }
}

function filterByIssueType(select) {
  var section = select.closest('.category-section');
  var catName = section.getAttribute('data-category');
  window.issueTypeFilters[catName] = select.value;
  window.applyFilters();
}
