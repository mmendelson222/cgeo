package cgeo.geocaching;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AutoCompleteTextView;
import android.widget.Button;

import org.apache.commons.lang3.StringUtils;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.Locale;

import butterknife.BindView;
import butterknife.ButterKnife;
import cgeo.geocaching.activity.AbstractActionBarActivity;
import cgeo.geocaching.activity.ShowcaseViewBuilder;
import cgeo.geocaching.address.AddressListActivity;
import cgeo.geocaching.connector.ConnectorFactory;
import cgeo.geocaching.connector.IConnector;
import cgeo.geocaching.connector.capability.ISearchByGeocode;
import cgeo.geocaching.connector.trackable.TrackableBrand;
import cgeo.geocaching.connector.trackable.TrackableTrackingCode;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.location.GeopointFormatter;
import cgeo.geocaching.search.AutoCompleteAdapter;
import cgeo.geocaching.sensors.GeoData;
import cgeo.geocaching.sensors.Sensors;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.storage.DataStore;
import cgeo.geocaching.ui.dialog.CoordinatesInputDialog;
import cgeo.geocaching.ui.dialog.Dialogs;
import cgeo.geocaching.utils.EditUtils;
import rx.functions.Func1;

public class SearchActivity extends AbstractActionBarActivity implements CoordinatesInputDialog.CoordinateUpdate {

    @BindView(R.id.buttonLatitude) protected Button buttonLatitude;
    @BindView(R.id.buttonLongitude) protected Button buttonLongitude;
    @BindView(R.id.search_coordinates) protected Button buttonSearchCoords;

    @BindView(R.id.address) protected AutoCompleteTextView addressEditText;
    @BindView(R.id.search_address) protected Button buttonSearchAddress;

    @BindView(R.id.geocode) protected AutoCompleteTextView geocodeEditText;
    @BindView(R.id.display_geocode) protected Button buttonSearchGeocode;

    @BindView(R.id.keyword) protected AutoCompleteTextView keywordEditText;
    @BindView(R.id.search_keyword) protected Button buttonSearchKeyword;

    @BindView(R.id.finder) protected AutoCompleteTextView finderNameEditText;
    @BindView(R.id.search_finder) protected Button buttonSearchFinder;

    @BindView(R.id.owner) protected AutoCompleteTextView ownerNameEditText;
    @BindView(R.id.search_owner) protected Button buttonSearchOwner;

    @BindView(R.id.trackable) protected AutoCompleteTextView trackableEditText;
    @BindView(R.id.display_trackable) protected Button buttonSearchTrackable;

    private static final String GOOGLE_NOW_SEARCH_ACTION = "com.google.android.gms.actions.SEARCH_ACTION";

    @Override
    public final void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent intent = getIntent();

        // search suggestion for a cache
        if (Intents.ACTION_GEOCACHE.equals(intent.getAction())) {
            CacheDetailActivity.startActivity(this, intent.getStringExtra(SearchManager.QUERY));
            finish();
            return;
        }

        // search suggestion for a trackable
        if (Intents.ACTION_TRACKABLE.equals(intent.getAction())) {
            TrackableActivity.startActivity(this, null, intent.getStringExtra(SearchManager.QUERY), null, null, TrackableBrand.UNKNOWN.getId());
            finish();
            return;
        }

        // search query, from search toolbar or from google now
        if (Intent.ACTION_SEARCH.equals(intent.getAction()) || GOOGLE_NOW_SEARCH_ACTION.equals(intent.getAction())) {
            hideKeyboard();
            final String query = intent.getStringExtra(SearchManager.QUERY);
            final boolean keywordSearch = intent.getBooleanExtra(Intents.EXTRA_KEYWORD_SEARCH, true);

            if (instantSearch(query, keywordSearch)) {
                setResult(RESULT_OK);
            } else {
                // send intent back so query string is known
                setResult(RESULT_CANCELED, intent);
            }
            finish();

            return;
        }

        setTheme();
        setContentView(R.layout.search_activity);

        // set title in code, as the activity needs a hard coded title due to the intent filters
        setTitle(res.getString(R.string.search));

        ButterKnife.bind(this);
        init();
    }

    @Override
    public final void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        init();
    }

    @Override
    public final void onResume() {
        super.onResume();
        init();
    }

    /**
     * Performs a search for query either as geocode, trackable code or keyword
     *
     * @param nonTrimmedQuery
     *            String to search for
     * @param keywordSearch
     *            Set to true if keyword search should be performed if query isn't GC or TB
     * @return true if a search was performed, else false
     */
    private boolean instantSearch(final String nonTrimmedQuery, final boolean keywordSearch) {
        final String query = StringUtils.trim(nonTrimmedQuery);

        // first check if this was a scanned URL
        String geocode = ConnectorFactory.getGeocodeFromURL(query);

        // otherwise see if this is a pure geocode
        if (StringUtils.isEmpty(geocode)) {
            geocode = StringUtils.upperCase(StringUtils.trim(query));
        }

        final IConnector connector = ConnectorFactory.getConnector(geocode);
        if (connector instanceof ISearchByGeocode && geocode != null) {
            CacheDetailActivity.startActivity(this, geocode.toUpperCase(Locale.US));
            return true;
        }

        // Check if the query is a TB code
        TrackableBrand trackableBrand = ConnectorFactory.getTrackableConnector(geocode).getBrand();

        // check if the query contains a TB code
        if (trackableBrand == TrackableBrand.UNKNOWN) {
            final String tbCode = ConnectorFactory.getTrackableFromURL(query);
            if (StringUtils.isNotBlank(tbCode)) {
                trackableBrand = ConnectorFactory.getTrackableConnector(tbCode).getBrand();
                geocode = tbCode;
            }
        }

        // check if the query contains a TB tracking code
        if (trackableBrand == TrackableBrand.UNKNOWN) {
            final TrackableTrackingCode tbTrackingCode = ConnectorFactory.getTrackableTrackingCodeFromURL(query);
            if (!tbTrackingCode.isEmpty()) {
                trackableBrand = tbTrackingCode.brand;
                geocode = tbTrackingCode.trackingCode;
            }
        }

        if (trackableBrand != TrackableBrand.UNKNOWN && geocode != null) {
            final Intent trackablesIntent = new Intent(this, TrackableActivity.class);
            trackablesIntent.putExtra(Intents.EXTRA_GEOCODE, geocode.toUpperCase(Locale.US));
            trackablesIntent.putExtra(Intents.EXTRA_BRAND, trackableBrand.getId());
            startActivity(trackablesIntent);
            return true;
        }

        if (keywordSearch) { // keyword fallback, if desired by caller
            CacheListActivity.startActivityKeyword(this, query.trim());
            return true;
        }

        return false;
    }

    private void init() {
        buttonLatitude.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(final View v) {
                updateCoordinates();
            }
        });
        buttonLongitude.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(final View v) {
                updateCoordinates();
            }
        });

        buttonSearchCoords.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(final View arg0) {
                findByCoordsFn();
            }
        });

        setSearchAction(addressEditText, buttonSearchAddress, new Runnable() {

            @Override
            public void run() {
                findByAddressFn();
            }
        }, null);

        setSearchAction(geocodeEditText, buttonSearchGeocode, new Runnable() {

            @Override
            public void run() {
                findByGeocodeFn();
            }
        }, new Func1<String, String[]>() {

            @Override
            public String[] call(final String input) {
                return DataStore.getSuggestionsGeocode(input);
            }
        });

        setSearchAction(keywordEditText, buttonSearchKeyword, new Runnable() {

            @Override
            public void run() {
                findByKeywordFn();
            }
        }, new Func1<String, String[]>() {

            @Override
            public String[] call(final String input) {
                return DataStore.getSuggestionsKeyword(input);
            }
        });

        setSearchAction(finderNameEditText, buttonSearchFinder, new Runnable() {

            @Override
            public void run() {
                findByFinderFn();
            }
        }, new Func1<String, String[]>() {

            @Override
            public String[] call(final String input) {
                return DataStore.getSuggestionsFinderName(input);
            }
        });

        setSearchAction(ownerNameEditText, buttonSearchOwner, new Runnable() {

            @Override
            public void run() {
                findByOwnerFn();
            }
        }, new Func1<String, String[]>() {

            @Override
            public String[] call(final String input) {
                return DataStore.getSuggestionsOwnerName(input);
            }
        });

        setSearchAction(trackableEditText, buttonSearchTrackable, new Runnable() {

            @Override
            public void run() {
                findTrackableFn();
            }
        }, new Func1<String, String[]>() {

            @Override
            public String[] call(final String input) {
                return DataStore.getSuggestionsTrackableCode(input);
            }
        });
    }

    private static void setSearchAction(final AutoCompleteTextView editText, final Button button, @NonNull final Runnable runnable, @Nullable final Func1<String, String[]> suggestionFunction) {
        EditUtils.setActionListener(editText, runnable);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View arg0) {
                runnable.run();
            }
        });
        if (suggestionFunction != null) {
            editText.setAdapter(new AutoCompleteAdapter(editText.getContext(), android.R.layout.simple_dropdown_item_1line, suggestionFunction));
        }
    }

    private void updateCoordinates() {
        final CoordinatesInputDialog coordsDialog = CoordinatesInputDialog.getInstance(null, null);
        coordsDialog.setCancelable(true);
        coordsDialog.show(getSupportFragmentManager(), "wpedit_dialog");
    }

    @Override
    public void updateCoordinates(final Geopoint gp) {
        buttonLatitude.setText(gp.format(GeopointFormatter.Format.LAT_DECMINUTE));
        buttonLongitude.setText(gp.format(GeopointFormatter.Format.LON_DECMINUTE));
    }

    private void findByCoordsFn() {
        final String latText = StringUtils.trim(buttonLatitude.getText().toString());
        final String lonText = StringUtils.trim(buttonLongitude.getText().toString());

        if (StringUtils.isEmpty(latText) || StringUtils.isEmpty(lonText)) {
            final GeoData geo = Sensors.getInstance().currentGeo();
            buttonLatitude.setText(geo.getCoords().format(GeopointFormatter.Format.LAT_DECMINUTE));
            buttonLongitude.setText(geo.getCoords().format(GeopointFormatter.Format.LON_DECMINUTE));
        } else {
            try {
                CacheListActivity.startActivityCoordinates(this, new Geopoint(latText, lonText), null);
            } catch (final Geopoint.ParseException e) {
                showToast(res.getString(e.resource));
            }
        }
    }

    private void findByKeywordFn() {
        // find caches by coordinates
        final String keyText = StringUtils.trim(keywordEditText.getText().toString());

        if (StringUtils.isBlank(keyText)) {
            Dialogs.message(this, R.string.warn_search_help_title, R.string.warn_search_help_keyword);
            return;
        }

        CacheListActivity.startActivityKeyword(this, keyText);
    }

    private void findByAddressFn() {
        final String addText = StringUtils.trim(addressEditText.getText().toString());

        if (StringUtils.isBlank(addText)) {
            Dialogs.message(this, R.string.warn_search_help_title, R.string.warn_search_help_address);
            return;
        }

        final Intent addressesIntent = new Intent(this, AddressListActivity.class);
        addressesIntent.putExtra(Intents.EXTRA_KEYWORD, addText);
        startActivity(addressesIntent);
    }

    private void findByFinderFn() {
        final String usernameText = StringUtils.trim(finderNameEditText.getText().toString());

        if (StringUtils.isBlank(usernameText)) {
            Dialogs.message(this, R.string.warn_search_help_title, R.string.warn_search_help_user);
            return;
        }

        CacheListActivity.startActivityFinder(this, usernameText);
    }

    private void findByOwnerFn() {
        findByOwnerFn(ownerNameEditText.getText().toString());
    }

    private void findByOwnerFn(final String userName) {
        final String usernameText = StringUtils.trimToEmpty(userName);

        if (StringUtils.isBlank(usernameText)) {
            Dialogs.message(this, R.string.warn_search_help_title, R.string.warn_search_help_user);
            return;
        }

        CacheListActivity.startActivityOwner(this, usernameText);
    }

    private void findByGeocodeFn() {
        final String geocodeText = StringUtils.trimToEmpty(geocodeEditText.getText().toString());

        if (StringUtils.isBlank(geocodeText) || geocodeText.equalsIgnoreCase("GC")) {
            Dialogs.message(this, R.string.warn_search_help_title, R.string.warn_search_help_gccode);
            return;
        }

        CacheDetailActivity.startActivity(this, geocodeText.toUpperCase(Locale.US));
    }

    private void findTrackableFn() {
        final String trackableText = StringUtils.trimToEmpty(trackableEditText.getText().toString());

        if (StringUtils.isBlank(trackableText) || trackableText.equalsIgnoreCase("TB")) {
            Dialogs.message(this, R.string.warn_search_help_title, R.string.warn_search_help_tb);
            return;
        }

        final Intent trackablesIntent = new Intent(this, TrackableActivity.class);
        trackablesIntent.putExtra(Intents.EXTRA_GEOCODE, trackableText.toUpperCase(Locale.US));
        startActivity(trackablesIntent);
    }

    @Override
    public final boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.search_activity_options, menu);
        presentShowcase();
        return true;
    }

    @Override
    public final boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.menu_search_own_caches) {
            findByOwnerFn(Settings.getUserName());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static void startActivityScan(final String scan, final Activity fromActivity) {
        final Intent searchIntent = new Intent(fromActivity, SearchActivity.class);
        searchIntent.setAction(Intent.ACTION_SEARCH).
                putExtra(SearchManager.QUERY, scan).
                putExtra(Intents.EXTRA_KEYWORD_SEARCH, false);
        fromActivity.startActivityForResult(searchIntent, Intents.SEARCH_REQUEST_CODE);
    }

    @Override
    public ShowcaseViewBuilder getShowcase() {
        // The showcase doesn't work well with the search activity, because on searching a geocode (or
        // selecting a cache from the search field) we immediately close the activity. That in turn confuses the delayed
        // creation of the showcase bitmap. To avoid someone running into this issue again, this method explicitly overrides
        // the parent method with the same implementation.
        return null;
    }
}
