package de.espend.idea.shopware.index;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.HashMap;
import com.intellij.util.indexing.*;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.jetbrains.php.lang.PhpFileType;
import com.jetbrains.php.lang.psi.PhpFile;
import com.jetbrains.php.lang.psi.elements.*;
import de.espend.idea.shopware.index.dict.ServiceResource;
import de.espend.idea.shopware.index.dict.ServiceResources;
import de.espend.idea.shopware.index.dict.SubscriberInfo;
import de.espend.idea.shopware.index.utils.SubscriberIndexUtil;
import fr.adrienbrault.idea.symfony2plugin.Symfony2ProjectComponent;
import fr.adrienbrault.idea.symfony2plugin.stubs.indexes.externalizer.ObjectStreamDataExternalizer;
import gnu.trove.THashMap;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class InitResourceServiceIndex extends FileBasedIndexExtension<String, ServiceResources> {

    public static final ID<String, ServiceResources> KEY = ID.create("de.espend.idea.shopware.init_resource_service_index_object");
    private final KeyDescriptor<String> myKeyDescriptor = new EnumeratorStringDescriptor();
    private final static ObjectStreamDataExternalizer<ServiceResources> EXTERNALIZER = new ObjectStreamDataExternalizer<>();

    @NotNull
    @Override
    public ID<String, ServiceResources> getName() {
        return KEY;
    }

    @NotNull
    @Override
    public DataIndexer<String, ServiceResources, FileContent> getIndexer() {
        return new DataIndexer<String, ServiceResources, FileContent>() {

            @NotNull
            @Override
            public Map<String, ServiceResources> map(@NotNull FileContent inputData) {
                final Map<String, ServiceResources> events = new THashMap<>();

                PsiFile psiFile = inputData.getPsiFile();
                if (!(psiFile instanceof PhpFile) || !Symfony2ProjectComponent.isEnabled(psiFile.getProject())) {
                    return events;
                }

                final Collection<Method> methodReferences = new ArrayList<>();

                psiFile.acceptChildren(new PsiRecursiveElementWalkingVisitor() {
                    @Override
                    public void visitElement(PsiElement element) {
                        if(element instanceof Method && "getSubscribedEvents".equals(((Method) element).getName())) {
                            methodReferences.add((Method) element);
                        }
                        super.visitElement(element);
                    }
                });

                if(methodReferences.size() == 0) {
                    return events;
                }

                //public static function getSubscribedEvents() {
                //  return [
                //  'Enlight_Bootstrap_InitResource_swagcoupons.basket_helper' => 'onInitBasketHelper',
                //  'Enlight_Bootstrap_InitResource_swagcoupons.settings' => 'onInitCouponSettings'
                // ];
                //}
                Map<String, Collection<ServiceResource>> serviceMap = new HashMap<>();
                for(final Method method : methodReferences) {
                    method.acceptChildren(new MyEventSubscriberVisitor(method, serviceMap));
                }

                // serialize to container object
                serviceMap.forEach((s, serviceResources) -> {
                    events.put(s, new ServiceResources(serviceResources));
                });

                return events;
            }
        };
    }

    @NotNull
    @Override
    public KeyDescriptor<String> getKeyDescriptor() {
        return this.myKeyDescriptor;
    }


    @NotNull
    @Override
    public DataExternalizer<ServiceResources> getValueExternalizer() {
        return EXTERNALIZER;
    }

    @NotNull
    @Override
    public FileBasedIndex.InputFilter getInputFilter() {
        return file -> file.getFileType() == PhpFileType.INSTANCE;
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return 8;
    }

    private static class MyEventSubscriberVisitor extends PsiRecursiveElementWalkingVisitor {

        @NotNull
        private final Method method;

        @NotNull
        private final Map<String, Collection<ServiceResource>> serviceMap;

        MyEventSubscriberVisitor(@NotNull Method method, @NotNull Map<String, Collection<ServiceResource>> serviceMap) {
            this.method = method;
            this.serviceMap = serviceMap;
        }

        @Override
        public void visitElement(PsiElement element) {
            if(element instanceof PhpReturn) {
                visitPhpReturn((PhpReturn) element);
            }

            super.visitElement(element);
        }

        private void visitPhpReturn(@NotNull PhpReturn phpReturn) {
            ArrayCreationExpression arrayCreationExpression = ObjectUtils.tryCast(phpReturn.getArgument(), ArrayCreationExpression.class);
            if(arrayCreationExpression == null) {
                return;
            }

            for (ArrayHashElement entry : arrayCreationExpression.getHashElements()) {
                StringLiteralExpression keyString = ObjectUtils.tryCast(entry.getKey(), StringLiteralExpression.class);
                if(keyString == null) {
                    continue;
                }


                String fullEvent = keyString.getContents();
                SubscriberInfo subscriberInfo = SubscriberIndexUtil.getSubscriberInfo(fullEvent);
                if(subscriberInfo == null) {
                    continue;
                }

                PhpPsiElement value = entry.getValue();
                if(value == null) {
                    continue;
                }

                String methodName = SubscriberIndexUtil.getMethodNameForEventValue(value);
                if(methodName == null || StringUtils.isBlank(methodName)) {
                    continue;
                }

                PhpClass phpClass = method.getContainingClass();
                if (phpClass == null) {
                    continue;
                }

                ServiceResource serviceResource = new ServiceResource(fullEvent, subscriberInfo.getEvent(), subscriberInfo.getService())
                    .setSignature(StringUtils.strip(phpClass.getFQN(), "\\") + '.' + methodName);

                String resourceKey = subscriberInfo.getEvent().getText();
                if(!serviceMap.containsKey(resourceKey)) {
                    serviceMap.put(resourceKey, new ArrayList<>());
                }

                serviceMap.get(resourceKey).add(serviceResource);
            }
        }
    }
}
