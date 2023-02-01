/*
 * Copyright (C) 2001-2016 Food and Agriculture Organization of the
 * United Nations (FAO-UN), United Nations World Food Programme (WFP)
 * and United Nations Environment Programme (UNEP)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
 *
 * Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
 * Rome - Italy. email: geonetwork@osgeo.org
 */
package org.fao.geonet.api.pages;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeeves.server.UserSession;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.fao.geonet.api.ApiParams;
import org.fao.geonet.api.ApiUtils;
import org.fao.geonet.api.exception.ResourceAlreadyExistException;
import org.fao.geonet.api.exception.ResourceNotFoundException;
import org.fao.geonet.api.exception.WebApplicationException;
import org.fao.geonet.api.tools.i18n.LanguageUtils;
import org.fao.geonet.domain.Profile;
import org.fao.geonet.domain.page.Page;
import org.fao.geonet.domain.page.PageIdentity;
import org.fao.geonet.repository.page.PageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RequestMapping(value = {"/{portal}/api/pages"})
@Tag(name = "pages", description = "Static pages inside GeoNetwork")
@Controller("pages")
public class PagesAPI {

    private static final String PAGE_OK = "Page found";
    private static final String PAGE_NOT_FOUND = "Page not found";
    private static final String PAGE_DUPLICATE = "Page already in the system: use PUT";
    private static final String PAGE_SAVED = "Page saved";
    private static final String PAGE_UPDATED = "Page changes saved";
    private static final String PAGE_DELETED = "Page removed";
    private static final String ERROR_FILE = "File not valid";
    private static final String ERROR_CREATE = "Wrong parameters are provided";

    @Autowired
    private PageRepository pageRepository;

    @Autowired
    private LanguageUtils languageUtils;

    @io.swagger.v3.oas.annotations.Operation(
        summary = "Add a page",
        description = "<p>Is not possible to load a link and a file at the same time.</p> <a href='http://geonetwork-opensource.org/manuals/4.0.x/eng/users/user-guide/define-static-pages/define-pages.html'>More info</a>")
    @PostMapping
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = PAGE_SAVED),
        @ApiResponse(responseCode = "400", description = ERROR_CREATE),
        @ApiResponse(responseCode = "409", description = PAGE_DUPLICATE),
        @ApiResponse(responseCode = "403", description = ApiParams.API_RESPONSE_NOT_ALLOWED_CAN_EDIT),
        @ApiResponse(responseCode = "500", description = ERROR_FILE)})
    @PreAuthorize("hasAuthority('Administrator')")
    @ResponseBody
    public ResponseEntity addPage(
        @RequestParam(value = "language", required = true) final String language,
        @RequestParam(value = "pageId", required = true) final String pageId,
        @RequestParam(value = "section", required = false) final Page.PageSection section[],
        @RequestParam(value = "status", required = false) final Page.PageStatus status,
        @RequestParam(value = "data", required = false) final MultipartFile data,
        @RequestParam(value = "link", required = false) final String link,
        @RequestParam(value = "format", required = false) Page.PageFormat format) throws ResourceAlreadyExistException {

        if (!StringUtils.isBlank(link)) {
            format = Page.PageFormat.LINK;
        }

        checkValidLanguage(language);

        checkMandatoryContent(data, link);

        checkUniqueContent(data, link);

        checkCorrectFormat(data, link, format);

        Optional<Page> page = pageRepository.findById(new PageIdentity(language, pageId));

        if (!page.isPresent()) {
            Page newPage = getEmptyHiddenDraftPage(language, pageId, format);
            fillContent(data, link, newPage);

            if (section != null) {
                List<Page.PageSection> sections = newPage.getSections();
                sections.clear();
                Arrays.asList(section).forEach(s -> {
                    sections.add(s);
                });
            }

            if (status != null) {
                newPage.setStatus(status);
            }

            pageRepository.save(newPage);
            return new ResponseEntity(HttpStatus.CREATED);
        } else {
            throw new ResourceAlreadyExistException();
        }
    }

    // Isn't done with PUT because the multipart support as used by Spring
    // doesn't support other request method than POST
    @io.swagger.v3.oas.annotations.Operation(
        summary = "Update a page",
        description = "<a href='http://geonetwork-opensource.org/manuals/4.0.x/eng/users/user-guide/define-static-pages/define-pages.html'>More info</a>")
    @PostMapping(
        value = "/{language}/{pageId}"
    )
    @ApiResponses(
        value = {
            @ApiResponse(responseCode = "200", description = PAGE_UPDATED),
            @ApiResponse(responseCode = "403", description = ApiParams.API_RESPONSE_NOT_ALLOWED_CAN_EDIT)
        })
    @PreAuthorize("hasAuthority('Administrator')")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ResponseEntity updatePage(
        @PathVariable(value = "language") final String language,
        @PathVariable(value = "pageId") final String pageId,
        @RequestParam(value = "newLanguage", required = false) final String newLanguage,
        @RequestParam(value = "newPageId", required = false) final String newPageId,
        @RequestParam(value = "section", required = false) final Page.PageSection section[],
        @RequestParam(value = "status", required = false) final Page.PageStatus status,
        @RequestParam(value = "data", required = false) final MultipartFile data,
        @RequestParam(value = "link", required = false) final String link,
        @RequestParam(value = "format", required = false) Page.PageFormat format
    ) throws ResourceNotFoundException, ResourceAlreadyExistException {
        if (!StringUtils.isBlank(link)) {
            format = Page.PageFormat.LINK;
        }

        checkValidLanguage(language);

        final Page page = searchPage(language, pageId, pageRepository);

        if (data != null || link != null) {
            checkUniqueContent(data, link);
            fillContent(data, link, page);
        }

        if (format != null) {
            checkCorrectFormat(data, link, format);
            page.setFormat(format);
        }

        if (section != null) {
            List<Page.PageSection> sections = page.getSections();
            sections.clear();
            Arrays.asList(section).forEach(s -> {
                sections.add(s);
            });
        }

        if (status != null) {
            page.setStatus(status);
        }

        if (!language.equals(newLanguage) || !pageId.equals(newPageId)) {
            String updatedLanguage = StringUtils.isBlank(newLanguage) ? language : newLanguage;
            String updatedPageId = StringUtils.isBlank(newPageId) ? pageId : newPageId;

            checkValidLanguage(updatedLanguage);

            Optional<Page> newPage = pageRepository.findById(new PageIdentity(updatedLanguage, updatedPageId));
            if (newPage.isPresent()) {
                throw new ResourceAlreadyExistException();
            }

            PageIdentity newId = new PageIdentity(updatedLanguage, updatedPageId);
            Page pageCopy = new Page(newId, page.getData(), page.getLink(),
                page.getFormat(), page.getSections(), page.getStatus());

            pageRepository.save(pageCopy);
            pageRepository.delete(page);
        } else {
            pageRepository.save(page);
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }


    @io.swagger.v3.oas.annotations.Operation(
        summary = "Delete a page",
        description = "<a href='http://geonetwork-opensource.org/manuals/4.0.x/eng/users/user-guide/define-static-pages/define-pages.html'>More info</a>")
    @DeleteMapping(
        value = "/{language}/{pageId}")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = PAGE_DELETED),
        @ApiResponse(responseCode = "404", description = PAGE_NOT_FOUND),
        @ApiResponse(responseCode = "403", description = ApiParams.API_RESPONSE_NOT_ALLOWED_CAN_EDIT)})
    @PreAuthorize("hasAuthority('Administrator')")
    @ResponseBody
    public void deletePage(
        @PathVariable(value = "language") final String language,
        @PathVariable(value = "pageId") final String pageId) throws ResourceNotFoundException {

        searchPage(language, pageId, pageRepository);
        pageRepository.deleteById(new PageIdentity(language, pageId));
    }

    @io.swagger.v3.oas.annotations.Operation(
        summary = "Return the page object details except the content",
        description = "<a href='http://geonetwork-opensource.org/manuals/4.0.x/eng/users/user-guide/define-static-pages/define-pages.html'>More info</a>")
    @GetMapping(
        value = "/{language}/{pageId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = PAGE_OK),
        @ApiResponse(responseCode = "404", description = PAGE_NOT_FOUND),
        @ApiResponse(responseCode = "403", description = ApiParams.API_RESPONSE_NOT_ALLOWED_CAN_VIEW)})
    @ResponseBody
    public ResponseEntity<PageJSONWrapper> getPage(
        @PathVariable(value = "language") final String language,
        @PathVariable(value = "pageId") final String pageId,
        @Parameter(hidden = true) final HttpSession session) throws ResourceNotFoundException {

        Page page = searchPage(language, pageId, pageRepository);
        return checkPermissionsOnSinglePageAndReturn(session, page);
    }

    @io.swagger.v3.oas.annotations.Operation(
        summary = "Return the static html content identified by pageId",
        description = "<a href='http://geonetwork-opensource.org/manuals/4.0.x/eng/users/user-guide/define-static-pages/define-pages.html'>More info</a>")
    @GetMapping(
        value = "/{language}/{pageId}/content",
        produces = "text/plain;charset=UTF-8")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = PAGE_OK),
        @ApiResponse(responseCode = "404", description = PAGE_NOT_FOUND),
        @ApiResponse(responseCode = "403", description = ApiParams.API_RESPONSE_NOT_ALLOWED_CAN_VIEW)})
    @ResponseBody
    public ResponseEntity<String> getPageContent(
        @PathVariable(value = "language") final String language,
        @PathVariable(value = "pageId") final String pageId,
        @Parameter(hidden = true) final HttpSession session) {


        final Optional<Page> page = pageRepository.findById(new PageIdentity(language, pageId));

        if (!page.isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } else {
            final UserSession us = ApiUtils.getUserSession(session);
            if (page.get().getStatus().equals(Page.PageStatus.HIDDEN) && us.getProfile() != Profile.Administrator) {
                return new ResponseEntity<>(HttpStatus.FORBIDDEN);
            } else if (page.get().getStatus().equals(Page.PageStatus.PRIVATE) && (us.getProfile() == null || us.getProfile() == Profile.Guest)) {
                return new ResponseEntity<>(HttpStatus.FORBIDDEN);
            } else {
                String content = "";
                if (page.get().getData() != null && page.get().getData().length > 0) {
                    content = new String(page.get().getData(), StandardCharsets.UTF_8);
                } else {
                    content = page.get().getLink();
                }

                return new ResponseEntity<>(content, HttpStatus.OK);
            }
        }
    }


    @io.swagger.v3.oas.annotations.Operation(
        summary = "List all pages according to the filters",
        description = "<a href='http://geonetwork-opensource.org/manuals/4.0.x/eng/users/user-guide/define-static-pages/define-pages.html'>More info</a>")
    @GetMapping
    @ApiResponses(value = {
        @ApiResponse(responseCode = "403", description = ApiParams.API_RESPONSE_NOT_ALLOWED_CAN_VIEW)})
    @ResponseBody
    public ResponseEntity<List<PageJSONWrapper>> listPages(
        @RequestParam(value = "language", required = false) final String language,
        @RequestParam(value = "section", required = false) final Page.PageSection section,
        @RequestParam(value = "format", required = false) final Page.PageFormat format,
        @Parameter(hidden = true) final HttpServletResponse response,
        @Parameter(hidden = true) final HttpSession session) {

        final UserSession us = ApiUtils.getUserSession(session);
        List<Page> unfilteredResult = null;

        if (language == null) {
            unfilteredResult = pageRepository.findAll();
        } else {
            unfilteredResult = pageRepository.findByPageIdentityLanguage(language);
        }

        final List<PageJSONWrapper> filteredResult = new ArrayList<>();

        for (final Page page : unfilteredResult) {
            if (page.getStatus().equals(Page.PageStatus.HIDDEN) && us.getProfile() == Profile.Administrator
                || page.getStatus().equals(Page.PageStatus.PRIVATE) && us.getProfile() != null && us.getProfile() != Profile.Guest
                || page.getStatus().equals(Page.PageStatus.PUBLIC)
                || page.getStatus().equals(Page.PageStatus.PUBLIC_ONLY) && !us.isAuthenticated()) {
                if (section == null || Page.PageSection.ALL.equals(section)) {
                    filteredResult.add(new PageJSONWrapper(page));
                } else {
                    final List<Page.PageSection> sections = page.getSections();
                    final boolean containsALL = sections.contains(Page.PageSection.ALL);
                    final boolean containsRequestedSection = sections.contains(section);
                    if (containsALL || containsRequestedSection) {
                        filteredResult.add(new PageJSONWrapper(page));
                    }
                }
            }
        }

        return new ResponseEntity<>(filteredResult, HttpStatus.OK);
    }

    @GetMapping(
        value = "/config/formats",
        produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = PAGE_OK)})
    @ResponseBody
    public Page.PageFormat[] getPageFormats() {
        return Page.PageFormat.values();
    }

    @GetMapping(
        value = "/config/sections",
        produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = PAGE_OK)})
    @ResponseBody
    public Page.PageSection[] getPageSections() {
        return Page.PageSection.values();
    }

    @GetMapping(
        value = "/config/status",
        produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = PAGE_OK)})
    @ResponseBody
    public Page.PageStatus[] getPageStatus() {
        return Page.PageStatus.values();
    }


    private void checkCorrectFormat(final MultipartFile data, final String link, final Page.PageFormat format) {
        if (Page.PageFormat.LINK.equals(format) && data != null && !data.isEmpty()) {
            throw new IllegalArgumentException("Wrong format. Cannot set format to LINK and upload a file.");
        }

        if (!Page.PageFormat.LINK.equals(format) && !StringUtils.isBlank(link)) {
            throw new IllegalArgumentException("Wrong format. Cannot set a link without setting format to LINK.");
        }
    }

    private void checkMandatoryContent(final MultipartFile data, final String link) {
        // Cannot set both: link and file
        if (StringUtils.isBlank(link) && (data == null || data.isEmpty())) {
            throw new IllegalArgumentException("A content associated to the page, a link or a file, is mandatory.");
        }
    }

    private void checkUniqueContent(final MultipartFile data, final String link) {
        // Cannot set both: link and file
        if (!StringUtils.isBlank(link) && data != null && !data.isEmpty()) {
            throw new IllegalArgumentException("A content associated to the page, a link or a file, is mandatory. But is not possible to associate both to the same page.");
        }
    }


    private void checkFileType(final MultipartFile data) {
        if (data != null) {
            String extension = FilenameUtils.getExtension(data.getOriginalFilename());
            if (!ArrayUtils.contains(Page.PageExtension.values(), extension.toUpperCase())) {
                throw new MultipartException("Unsuppoted file type (only html, txt and md are allowed).");
            }
        }
    }

    private void checkValidLanguage(String language) {
        if (!languageUtils.getUiLanguages().contains(language)) {
            throw new IllegalArgumentException(
                String.format("Language value is not valid: %s. A valid application language is mandatory: %s.",
                    language,
                    String.join(",", languageUtils.getUiLanguages())));
        }
    }

    /**
     * Check permissions on single page and return.
     *
     * @param session the session
     * @param page    the page
     * @return the response entity
     */
    private ResponseEntity<PageJSONWrapper> checkPermissionsOnSinglePageAndReturn(final HttpSession session, final Page page) {
        if (page == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        } else {
            final UserSession us = ApiUtils.getUserSession(session);
            if (page.getStatus().equals(Page.PageStatus.HIDDEN) && us.getProfile() != Profile.Administrator) {
                return new ResponseEntity<>(HttpStatus.FORBIDDEN);
            } else if (page.getStatus().equals(Page.PageStatus.PRIVATE) && (us.getProfile() == null || us.getProfile() == Profile.Guest)) {
                return new ResponseEntity<>(HttpStatus.FORBIDDEN);
            } else {
                return new ResponseEntity<>(new PageJSONWrapper(page), HttpStatus.OK);
            }
        }
    }

    /**
     * Search page.
     *
     * @param language       the language
     * @param pageId         the page id
     * @param pageRepository the page repository
     * @return the page
     * @throws ResourceNotFoundException the resource not found exception
     */
    private Page searchPage(final String language, final String pageId, final PageRepository pageRepository)
        throws ResourceNotFoundException {
        final Optional<Page> page = pageRepository.findById(new PageIdentity(language, pageId));

        if (!page.isPresent()) {
            throw new ResourceNotFoundException("Page " + pageId + " not found");
        }
        return page.get();
    }

    /**
     * @param language
     * @param pageId
     * @param format
     * @return An empty hidden draft Page
     */
    protected Page getEmptyHiddenDraftPage(final String language, final String pageId, final Page.PageFormat format) {
        final List<Page.PageSection> sections = new ArrayList<>();
        sections.add(Page.PageSection.DRAFT);
        return new Page(new PageIdentity(language, pageId), null, null, format, sections, Page.PageStatus.HIDDEN);
    }

    /**
     * Set the content with file or with provided link
     *
     * @param data the file
     * @param link the link
     * @param page the page to set content
     */
    private void fillContent(final MultipartFile data, final String link, final Page page) {
        byte[] bytesData = null;
        if (data != null && !data.isEmpty()) {
            checkFileType(data);
            try {
                bytesData = data.getBytes();
            } catch (final Exception e) {
                // Wrap into managed exception
                throw new WebApplicationException(e);
            }
            page.setData(bytesData);
        }

        if (link != null && !UrlUtils.isValidRedirectUrl(link)) {
            throw new IllegalArgumentException("The link provided is not valid");
        } else {
            page.setLink(link);
        }
    }
}
